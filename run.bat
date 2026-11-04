@echo off
setlocal
if not exist "lib\mysql-connector-j-8.4.0.jar" (
    echo MySQL Connector/J was not found in lib.
    exit /b 1
)
javac -cp ".;lib\mysql-connector-j-8.4.0.jar" SubscriberApp.java
if errorlevel 1 exit /b 1
java -cp ".;lib\mysql-connector-j-8.4.0.jar" SubscriberApp
