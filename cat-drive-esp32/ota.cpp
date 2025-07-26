#ifdef USE_OTA
#include "ota.h"

#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <Update.h>

#include "pages/index.html.h"

WebServer server(80);
OtaUpdateCallback gUpdateCallback = nullptr;
size_t gUploadedSize = 0;
size_t gFileSize = 0;
String gFileName;
UpdateStatus gUpdateStatus = UpdateStatus::None;

const char* redirect_html = "<script>"
                            "    window.location.href = '/main'"
                            "</script>";

void CatDriveOTA::begin(const char* host, const char* ssid, const char* password) {
	WiFi.mode(WIFI_AP_STA);
	WiFi.softAP(ssid, password);

	server.on("/", HTTP_GET, []() {
		server.sendHeader("Connection", "close");
		server.send(200, "text/html", redirect_html);
	});

	server.on("/main", HTTP_GET, []() {
		server.sendHeader("Connection", "close");
		server.send(200, "text/html", index_html);
	});

	server.on("/updateFileSize", HTTP_PUT, []() {
		if (server.hasArg("plain")) {
			gFileSize = server.arg("plain").toInt();
		}
		server.sendHeader("Connection", "close");
		server.send(200, "text/plain", "OK");
	});

	server.on(
	  "/update", HTTP_POST, []() {
		  server.sendHeader("Connection", "close");
		  server.send(200, "text/plain", (Update.hasError()) ? "FAIL" : "OK");
		  ESP.restart();
	  },
	  []() {
		  HTTPUpload& upload = server.upload();
		  UpdateStatus status;

		  if (upload.status == UPLOAD_FILE_START) {
			  Serial.printf("Update: %s\n", upload.filename.c_str());
			  status = UpdateStatus::UpdateStart;

			  if (!Update.begin(UPDATE_SIZE_UNKNOWN)) {
				  Update.printError(Serial);
				  status = UpdateStatus::UpdateFailed;
			  }
		  } else if (upload.status == UPLOAD_FILE_WRITE) {
			  status = UpdateStatus::Updating;
			  if (Update.write(upload.buf, upload.currentSize) != upload.currentSize) {
				  Update.printError(Serial);
				  status = UpdateStatus::UpdateFailed;
			  }
		  } else if (upload.status == UPLOAD_FILE_END) {
			  status = UpdateStatus::UpdateSuccedded;
			  // true to set the size to the current progress
			  if (Update.end(true)) {
				  Serial.printf("Update Success: %u\nRebooting...\n", upload.totalSize);
			  } else {
				  Update.printError(Serial);
				  status = UpdateStatus::UpdateFailed;
			  }
		  }

		  gFileName = upload.filename;
		  gUploadedSize = upload.totalSize;
		  gUpdateStatus = status;
		  if (gUpdateCallback)
			  gUpdateCallback(status, upload.filename, upload.totalSize, gFileSize);
	  });
	server.begin();
}

void CatDriveOTA::handle() {
	server.handleClient();
}

String CatDriveOTA::getAPIP() {
	return WiFi.softAPIP().toString();
}

UpdateStatus CatDriveOTA::status() {
	return UpdateStatus();
}

size_t CatDriveOTA::uploadedSize() {
	return gUploadedSize;
}

size_t CatDriveOTA::totalSize() {
	return gFileSize;
}

String CatDriveOTA::fileName() {
	return gFileName;
}

void CatDriveOTA::attachUpdateCallback(OtaUpdateCallback callback) {
	gUpdateCallback = callback;
}

#endif