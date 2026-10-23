The ftps-test.p12 fixture is a PUBLIC, TEST-ONLY EC key/certificate pair.
Password and key password: test-only. Alias: ftps-test.
It is loaded only by JVM tests under src/test, never by application code.
Production continues to load a unique device identity through FtpsIdentity.

Generated with JDK keytool:
keytool -genkeypair -alias ftps-test -keyalg EC -groupname secp256r1 -dname "CN=localhost,OU=TEST ONLY" -validity 36500 -storetype PKCS12 -keystore ftps-test.p12 -storepass test-only -keypass test-only -noprompt
