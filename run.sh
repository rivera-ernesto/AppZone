. run.properties

function usage(){
    echo "Usage : $0 start [skipbuild]"
    echo ""
    echo "        Edit prop.properties for configuration of ports and domain"
    exit
}

function kill_all_jobs { jobs -p | xargs kill; exit; }
trap kill_all_jobs SIGINT INT

if [ $# -lt 1 ]
then
  usage
fi

originalFolder=`pwd`

case "$1" in
start)
    echo "Starting..."

    cd server

    PROPS_FOLDER=src/main/resources/props
    PROPS_FILE=$PROPS_FOLDER/default.props
    mkdir $PROPS_FOLDER
    echo "mongo.host=$mongo_host" > $PROPS_FILE
    echo "mongo.port=$mongo_port" >> $PROPS_FILE
    echo "mongo.db=$mongo_db" >> $PROPS_FILE

    if [ "$2" != "skipbuild" ]
    then
      sbt clean assembly
    fi
    export PORT=$api_port
    java -jar target/scala-2.9.2/*-assembly-*.jar &

    cd ../server-static/
    perl -p -i -e "s/http:\/\/[^\/]+\//http:\/\/$api_ext_domain:$api_ext_port\//g" js/appzone.js
    python -m SimpleHTTPServer $web_port &

    wait
    ;;
*)    usage ;;
esac

cd $originalFolder