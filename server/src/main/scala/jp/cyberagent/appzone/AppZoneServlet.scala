package jp.cyberagent.appzone

import org.scalatra._
import org.scalatra.servlet.FileUploadSupport
import scalate.ScalateSupport
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import net.liftweb.mongodb.MongoDB
import net.liftweb.mongodb.DefaultMongoIdentifier
import net.liftweb.mongodb.Upsert
import com.mongodb._
import com.mongodb.BasicDBObjectBuilder
import com.mongodb.gridfs.GridFS
import java.io.FileWriter
import java.io.FileOutputStream
import java.io.File
import java.util.Date
import net.liftweb.util.Props
import org.scalatra.BadRequest
import java.io.InputStream
import org.scalatra.servlet.FileItem
import net.liftweb.http.RedirectResponse
import java.net.URLEncoder
import java.lang.String
import java.io.ByteArrayInputStream
import scala.io.Source

class AppZoneServlet extends ScalatraServlet with ScalateSupport with JsonHelpers with FileUploadSupport with CorsSupport {

  get("/apps") {
    Json(App.findAll.map(p => p.asJValue))
  }

  post("/app") {
    val app = App.createRecord
    app.id.set(params.get("id").getOrElse(""))
    app.name.set(params.get("name").getOrElse(""))
    App.update(("id" -> app.id.asJValue), app, Upsert)
    Json(app.asJValue)
  }

  get("/app/:id") {
    val res = App.find(("id" -> params("id")))
    if (res.isEmpty)
      resourceNotFound()
    else
      Json(res.get.asJValue)
  }

  get("/app/:id/android") {
    val appId = params("id")
    MongoDB.use(DefaultMongoIdentifier) { db =>
      val fs = new GridFS(db)
      val file = fs.findOne(appId + "/android.apk")
      if (file != null) {
        response.setHeader("Content-Type", "application/vnd.android.package-archive")
        response.setHeader("Content-Disposition", "attachment; filename=\"" + appId + ".apk\"")
        response.setHeader("Content-Length", file.getLength().toString)
        org.scalatra.util.io.copy(file.getInputStream(), response.getOutputStream)
      } else {
        resourceNotFound()
      }
    }
  }

  post("/app/:id/android") {
    val appId = params("id")
    fileParams.get("apk") match {
      case Some(file) =>
        storeFile(file, appId + "/android.apk")
        val appRes = App.find(("id" -> appId))
        appRes match {
          case Full(app) => {
            val record: AppPlatformEntry = app.android.valueBox.openOr(AppPlatformEntry.createRecord)
            record.version.set(params.getOrElse("version", "NOT SET"))
            record.incrementVersionCode
            record.setDateToNow
            app.android.set(record)
            App.update(("id" -> appId), app)
            Json(app.asJValue)
          }
          case _ => resourceNotFound()
        }
      case None =>
        BadRequest("apk (file) parameter required")
    }
  }

  post("/app/:id/ios") {
    val appId = params("id")
    fileParams.get("ipa") match {
      case Some(ipaFile) =>
        fileParams.get("manifest") match {
          case Some(manifestFile) =>
            storeFile(ipaFile, appId + "/ios.ipa")
            storeFile(manifestFile, appId + "/ios.manifest")
            val appRes = App.find(("id" -> appId))
            appRes match {
              case Full(app) => {
                val record: AppPlatformEntry = app.ios.valueBox.openOr(AppPlatformEntry.createRecord)
                record.version.set(params.getOrElse("version", "NOT SET"))
                record.incrementVersionCode
                record.setDateToNow
                app.ios.set(record)
                App.update(("id" -> appId), app)
                Json(app.asJValue)
              }
              case _ => resourceNotFound()
            }
          case _ => BadRequest("manifest (file) parameter required")
        }
      case None =>
        BadRequest("ipa (file) parameter required")
    }
  }

  get("/app/:id/ios") {
    val url = URLEncoder.encode(request.getRequestURL().toString() + ".manifest", "UTF-8");
    redirect("itms-services://?action=download-manifest&url=" + url)
  }

  get("/app/:id/ios.manifest") {
    val appId = params("id")
    MongoDB.use(DefaultMongoIdentifier) { db =>
      val fs = new GridFS(db)
      val file = fs.findOne(appId + "/ios.manifest")
      if (file != null) {
        response.setHeader("Content-Type", "text/xml")
        val content = Source.fromInputStream(file.getInputStream()).getLines.mkString("\n")
        val url = request.getRequestURL().toString
        val contentNew = """<string>.*\.ipa</string>""".r.replaceFirstIn(content, "<string>" + url.substring(0, url.lastIndexOf(".")) + ".ipa</string>")
        val input = new ByteArrayInputStream(contentNew.getBytes("UTF-8"));
        org.scalatra.util.io.copy(input, response.getOutputStream)
      } else {
        resourceNotFound()
      }
    }
  }

  get("/app/:id/ios.ipa") {
    val appId = params("id")
    MongoDB.use(DefaultMongoIdentifier) { db =>
      val fs = new GridFS(db)
      val file = fs.findOne(appId + "/ios.ipa")
      if (file != null) {
        response.setHeader("Content-Type", "application/octet-stream")
        response.setHeader("Content-Disposition", "attachment; filename=\"" + appId + ".ipa\"")
        response.setHeader("Content-Length", file.getLength().toString)
        org.scalatra.util.io.copy(file.getInputStream(), response.getOutputStream)
      } else {
        resourceNotFound()
      }
    }
  }

  post("/app/:id/feedback") {
    storeFeedback(params("id"), params("type"), params("feedback"))
  }
  
  post("/app/:id/android/feedback") {
    storeFeedback(params("id"), "android", params("feedback"))
  }
  post("/app/:id/ios/feedback") {
    storeFeedback(params("id"), "ios", params("feedback"))
  }

  get("/app/:id/feedback") {
    Json(Feedback.findAll(("appId" -> params("id"))).map(p => p.asJValue))
  }

  notFound {
    // remove content type in case it was set through an action
    contentType = null
    // Try to render a ScalateTemplate if no route matched
    findTemplate(requestPath) map { path =>
      contentType = "text/html"
      layoutTemplate(path)
    } orElse serveStaticResource() getOrElse resourceNotFound()
  }

  def storeFile(file: FileItem, fileName: String) {
    val input = file.getInputStream
    MongoDB.use(DefaultMongoIdentifier) { db =>
      val fs = new GridFS(db)
      fs.remove(fileName)
      val inputFile = fs.createFile(input)
      inputFile.setFilename(fileName)
      inputFile.setContentType(file.contentType.getOrElse("application/octet-stream"))
      inputFile.save
    }
  }

  def storeFeedback(id: String, appType: String, feedback: String) = {
    val feedbackRecord = Feedback.createRecord
    feedbackRecord.appId.set(id)
    feedbackRecord.appType.set(appType)
    feedbackRecord.feedback.set(feedback)
    feedbackRecord.save
    Json(feedbackRecord.asJValue)
  }
}
