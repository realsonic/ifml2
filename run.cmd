xcopy /y out\artifacts\ifml2\*.* "test run\*.*"
xcopy /e /y Games "test run\Games\"
xcopy /e /y libs "test run\libs\"
xcopy /e /y Tests "test run\Tests\"
cd "test run"

java -version:"1.6*" -Dlog4j.configuration=file:log4j.xml -jar ifml2.jar "player"

rem java -Dlog4j.configuration=file:log4j.xml -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005 -jar ifml2.jar "player"

rem pause