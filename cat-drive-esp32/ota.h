#ifndef CATDRIVEOTA_H
#define CATDRIVEOTA_H

#ifdef USE_OTA

#include <Arduino.h>

enum class UpdateStatus {
	None,
	UpdateStart,
	Updating,
	UpdateSuccedded,
	UpdateFailed
};

typedef void (*OtaUpdateCallback)(UpdateStatus, String, int, int);

namespace CatDriveOTA {
void begin(const char* host, const char* ssid, const char* password);
void handle();
String getAPIP();

UpdateStatus status();
size_t uploadedSize();
size_t totalSize();
String fileName();

void attachUpdateCallback(OtaUpdateCallback callback);
}

#endif
#endif