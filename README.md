# sap-adapter
SAP transport implementation

## Build Instructions

1. Copy **sapidoc3.jar** and **sapjco3.jar** to a directory. Lets call this <SAP_JARS_DIR>
2. Build the repository using the following command 
```
 mvn -Dsap.lib.dir=<SAP_JARS_DIR>  clean install
```