#include <Arduino.h>
#include <ArduinoJson.h>
#include <BluetoothSerial.h>
#include <stack>
#include <base64.hpp>

#include "scrollabletext.h"
#include "splash.h"
#include <Fonts/Org_01.h>
#include <U8g2_for_Adafruit_GFX.h>

#include <SPI.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

#define SCREEN_WIDTH 128  // OLED display width, in pixels
#define SCREEN_HEIGHT 64  // OLED display height, in pixels

// Declaration for an SSD1306 display connected to I2C (SDA, SCL pins)
// The pins for I2C are defined by the Wire-library.
#define OLED_RESET -1        // Reset pin # (or -1 if sharing Arduino reset pin)
#define SCREEN_ADDRESS 0x3C  ///< See datasheet for Address; 0x3D for 128x64, 0x3C for 128x32
Adafruit_SSD1306 lcd(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

// Over-the-Air update, it serve a webpage that you can upload prebuilt binary into it,
// Good when you have put the device in a sealed box and cannot access the board to upload with USB cable
// NOTE: Not working in July 2025, maybe the protocol has changed somehow in Arduino V2, need to investigate
// #define USE_OTA
#ifdef USE_OTA
#include "ota.h"
#endif

// #define TEST_FONT

#define SCHEDULER_SOURCE millis()
#include "Scheduler.h"

#include "fonts/helvB12.h"
//#include "src/fonts/luBS10.h"
#include "fonts/luRS10.h"

namespace BacklightControl {
namespace detail {
uint32_t lastFlashRequest_ms = 0;
uint32_t offWithTimerStart_ms = 0;
int8_t toggleCount = -1;
bool isOn = false;
bool isWaitingForOff = false;
}

void flashScreen() {
	if (millis() > detail::lastFlashRequest_ms + 5000) {
		detail::lastFlashRequest_ms = millis();
		detail::toggleCount = 0;
	}
}

void offWithTimer() {
	if (detail::isOn == false || detail::isWaitingForOff)
		return;
	detail::isWaitingForOff = true;

	// Cancel the flashing
	detail::toggleCount = -1;
	lcd.invertDisplay(detail::isOn);

	detail::offWithTimerStart_ms = millis();
}

void on() {
	detail::isOn = true;
	detail::toggleCount = -1;
	detail::isWaitingForOff = false;
	lcd.invertDisplay(true);
}

void off() {
	detail::isOn = false;
	detail::toggleCount = -1;
	detail::isWaitingForOff = false;
	lcd.invertDisplay(false);
}

void update() {
	if (detail::isWaitingForOff) {
		constexpr uint32_t TIMEOUT = 5000;
		if (millis() > detail::offWithTimerStart_ms + TIMEOUT) {
			off();
		}
	}

	if (detail::toggleCount != -1) {
		constexpr int8_t MAX_CYCLE = 2;
		DO_EVERY(100) {
			detail::toggleCount++;
			if (detail::toggleCount == MAX_CYCLE * 2) {
				lcd.invertDisplay(detail::isOn);
				detail::toggleCount = -1;
			} else {
				lcd.invertDisplay(detail::toggleCount % 2);
			}
		}
	}
}
}

namespace UI {
ScrollableText nextRoad(128, 18);
ScrollableText nextRoadDesc(128, 14);
ScrollableText distance(51, 12);
ScrollableText eta(90, 12);

void setup() {
	nextRoad.setScrollMode(ScrollableText::ScrollMode::Loop);
	nextRoad.setHorizontalAlignment(ScrollableText::HorizontalAlignment::Center);
	nextRoad.setU8g2Font(helvB12);
	nextRoad.setYOffset(2);
	nextRoad.setScrollInterval(100);
	nextRoad.setScrollSteps(6);

	nextRoadDesc.setScrollMode(ScrollableText::ScrollMode::Loop);
	nextRoadDesc.setHorizontalAlignment(ScrollableText::HorizontalAlignment::Center);
	nextRoadDesc.setScrollInterval(100);
	nextRoadDesc.setScrollSteps(6);
	nextRoadDesc.setU8g2Font(luRS10);
	nextRoadDesc.setYOffset(3);

	//		distance.setU8g2Font(luBS10);
	distance.setU8g2Font(helvB12);
	distance.reset();

	eta.setU8g2Font(u8g2_font_baby_tf);
	eta.setScrollInterval(100);
	eta.setScrollSteps(1);
	eta.setScrollMode(ScrollableText::ScrollMode::Loop);
}
}

namespace Pref {
bool backlight = false;
int speedLimit = 60;
}

namespace Data {
namespace details {
int speed = -1;
String nextRoad = String();
String nextRoadDesc = String();
String eta = String();
String distanceToNextTurn = String();
unsigned char iconBuffer[128 + 64];
}

bool hasNavigationData() {
	return !(details::nextRoad.isEmpty()
	         && details::nextRoadDesc.isEmpty()
	         && details::eta.isEmpty()
	         && details::distanceToNextTurn.isEmpty());
}

bool hasSpeedData() {
	return details::speed >= 0;
}

void clearNavigationData() {
	details::nextRoad.clear();
	details::nextRoadDesc.clear();
	details::eta.clear();
	details::distanceToNextTurn.clear();
	memset(details::iconBuffer, 0, sizeof(details::iconBuffer));
}
void clearSpeedData() {
	details::speed = -1;
}

int speed() {
	return std::max(details::speed, 0);
}
void setSpeed(const int& value) {
	details::speed = value;
}
String nextRoad() {
	return hasNavigationData() ? details::nextRoad : "---";
}
void setNextRoad(const String& value) {
	if (!value.isEmpty() && value != details::nextRoad) {
		BacklightControl::flashScreen();
	}
	details::nextRoad = value;
}
String nextRoadDesc() {
	return hasNavigationData() ? details::nextRoadDesc : "---";
}
void setNextRoadDesc(const String& value) {
	details::nextRoadDesc = value;
}
String eta() {
	return hasNavigationData() ? details::eta : "--- - --- - ---";
}
void setEta(const String& value) {
	details::eta = value;
}
String distanceToNextTurn() {
	return hasNavigationData() ? details::distanceToNextTurn : "---";
}
void setDistanceToNextTurn(const String& value) {
	details::distanceToNextTurn = value;
}
}

BluetoothSerial bt;
StaticJsonDocument<2048> gJsonDocument;
std::stack<String> gBtDataQueue;

uint32_t gLastNavigationDataReceived_ms = 0;
uint32_t gLastSpeedDataReceived_ms = 0;


void pongNavigation() {
	gLastNavigationDataReceived_ms = millis();
}

void pongSpeed() {
	gLastSpeedDataReceived_ms = millis();
}

void drawHashedHLine(Adafruit_GFX& gfx, int16_t x, int16_t y, int16_t w, int16_t spacing = 1) {
	for (int16_t i = x; i < x + w; ++i)
		if ((i / spacing) % 2)
			gfx.drawPixel(i, y, WHITE);
}

void drawHashedVLine(Adafruit_GFX& gfx, int16_t x, int16_t y, int16_t h, int16_t spacing = 1) {
	for (int16_t i = y; i < y + h; ++i)
		if ((i / spacing) % 2)
			gfx.drawPixel(x, i, WHITE);
}

void drawTextAsMonospaced(Adafruit_GFX& gfx, int16_t x, int16_t y, int16_t spacing, const String& text, uint16_t color) {
	int16_t len = text.length();
	int currentX = x;

	lcd.setTextColor(color);
	for (int16_t i = 0; i < len; ++i) {
		char c = text.charAt(i);
		int16_t _x, _y;
		uint16_t _w, _h;
		// Get char width
		gfx.getTextBounds(String(c), 0, 0, &_x, &_y, &_w, &_h);

		// Align text at the right side of its place
		// lcd.setCursor(currentX + (spacing - _w), y);
		// Align text at the horizontal center of its place
		lcd.setCursor(currentX + (spacing - _w) / 2, y);

		lcd.print(c);
		currentX += spacing;
	}
}

void onBluetoothData(const uint8_t* buffer, size_t size) {
	gBtDataQueue.emplace(buffer, size);
}

template<class T>
T getOrDefault(const JsonVariantConst& json, const String& key, const T& defaultValue = T()) {
	if (json[key].is<T>())
		return json[key].as<T>();
	return defaultValue;
}

void parseJson(const String& jsonData) {
	Serial.println(jsonData);

	const auto& root = gJsonDocument;
	const auto error = deserializeJson(gJsonDocument, jsonData);

	if (error.code() != DeserializationError::Ok) {
		Serial.print("deserialize error: ");
		Serial.println(error.c_str());
		return;
	}

	if (root.containsKey("preferences")) {
		const auto& pref = root["preferences"];
		const auto backlight = getOrDefault<bool>(pref, "display_backlight", false);
		const auto speedLimit = getOrDefault<int>(pref, "speed_limit", 60);

		Pref::backlight = backlight;
		Pref::speedLimit = speedLimit;
		Pref::backlight ? BacklightControl::on() : BacklightControl::off();
	}

	if (root.containsKey("navigation")) {
		pongNavigation();
		const auto& navigation = root["navigation"];
		Data::setNextRoad(getOrDefault<String>(navigation, "next_road"));
		Data::setNextRoadDesc(getOrDefault<String>(navigation, "next_road_sub"));
		Data::setDistanceToNextTurn(getOrDefault<String>(navigation, "next_road_distance"));

		const auto eta = getOrDefault<String>(navigation, "eta");
		const auto ete = getOrDefault<String>(navigation, "ete");
		const auto totalDistance = getOrDefault<String>(navigation, "distance");
		const auto encodedIcon = getOrDefault<String>(navigation, "icon");

		unsigned char buffer[256];
		memcpy(buffer, encodedIcon.c_str(), encodedIcon.length());
		const auto len = decode_base64(buffer, encodedIcon.length(), Data::details::iconBuffer);
		//Serial.print("decode len: ");
		//Serial.println(len);

		if (!eta.isEmpty() || !ete.isEmpty() || !totalDistance.isEmpty())
			Data::setEta(ete + " - " + totalDistance + " - " + eta);
		else
			Data::setEta(String());
	}

	if (root.containsKey("speed")) {
		pongSpeed();
		Data::setSpeed(getOrDefault<int>(root, "speed", 0));
	}
}

void drawSplashScreen(const String& text = "CatDrive", int16_t xOffset = 0, int16_t yOffset = 0) {
	lcd.drawBitmap(32 + xOffset, -9 + yOffset, splash, 64, 64, WHITE);
	lcd.setFont(&Org_01);
	lcd.setTextSize(1);
	lcd.setTextColor(WHITE);
	int16_t x, y;
	uint16_t w, h;
	lcd.getTextBounds(text, 0, 0, &x, &y, &w, &h);
	lcd.setCursor((lcd.width() - w) / 2 + xOffset, lcd.height() - 7 + yOffset);
	lcd.print(text);
}

#ifdef USE_OTA
void otaUpdateCallback(UpdateStatus status, String filename, int received, int total) {
	if (status == UpdateStatus::UpdateStart) {
		if (filename.length() > 30)
			filename = "..." + filename.substring(filename.length() - 30);

		lcd.fillScreen(BLACK);
		drawSplashScreen(filename);
		lcd.display();
	} else if (status == UpdateStatus::UpdateSuccedded) {
		lcd.fillScreen(BLACK);
		drawSplashScreen("COMPLETE");
		lcd.display();
		delay(2000);
	} else if (status == UpdateStatus::UpdateFailed) {
		lcd.fillScreen(BLACK);
		drawSplashScreen("FAILED");
		lcd.display();
		delay(2000);
	} else if (status == UpdateStatus::Updating) {
		DO_EVERY(300) {
			float percent = float(received) / (total);
			lcd.fillScreen(BLACK);
			drawSplashScreen("");
			const auto w = 127 - 15 - 15;
			lcd.drawRoundRect(15, 55, w, 4, 2, WHITE);
			lcd.fillRoundRect(15, 55, w * percent, 4, 2, WHITE);
			lcd.display();
		}
	}
}
#endif

void setup() {
	Serial.begin(115200);
	bt.begin("CatDrive");
	bt.onData(onBluetoothData);

	Serial.println("\nHello!");

	// SSD1306_SWITCHCAPVCC = generate display voltage from 3.3V internally
	if (!lcd.begin(SSD1306_SWITCHCAPVCC, SCREEN_ADDRESS)) {
		Serial.println(F("SSD1306 allocation failed"));
		for (;;)
			;  // Don't proceed, loop forever
	}

	lcd.fillScreen(WHITE);
	lcd.fillScreen(BLACK);

	BacklightControl::off();

	UI::setup();

#ifdef TEST_FONT
	UI::nextRoad.setText("áàảãạ ăắằẳẵặ âấầẩẫậ eéèẻẽẹ êếềểễệ íìỉĩị ôốồổỗộ ơớờởỡợ ưứừửữự đường xá cảnh quang");
	UI::nextRoad.setScrollInterval(2000);
#endif

	for (int16_t i = -20; i <= 0; i += 2) {
		// Splash screen
		lcd.fillScreen(BLACK);
		drawSplashScreen("CatDrive", 0, i);
		lcd.display();
		delay(100);
	}

	delay(1000);

	lcd.fillScreen(BLACK);
#ifdef USE_OTA
	// This will take several seconds
	CatDriveOTA::attachUpdateCallback(otaUpdateCallback);
	CatDriveOTA::begin("catdriveota", "CatDrive OTA", "12121212");
	drawSplashScreen("OTA: " + CatDriveOTA::getAPIP());
#else
	drawSplashScreen();
#endif

	lcd.display();
	delay(3000);
}

bool isOverspeed(int speed) {
	return speed >= Pref::speedLimit;
}

void loop() {
#ifdef USE_OTA
	CatDriveOTA::handle();
	if (CatDriveOTA::status() != UpdateStatus::None)
		return;
#endif

#ifdef TEST_FONT
	lcd.fillScreen(BLACK);
	UI::nextRoad.update();
	const auto drawCanvas = [](Adafruit_GFX& gfx, const GFXcanvas1& canvas, int16_t x, int16_t y) {
		lcd.drawBitmap(x, y, canvas.getBuffer(), canvas.width(), canvas.height(), WHITE);
	};
	drawCanvas(lcd, UI::nextRoad, 0, 38);
	lcd.display();
#else
	while (!gBtDataQueue.empty()) {
		parseJson(gBtDataQueue.top());
		gBtDataQueue.pop();
	}

	constexpr uint32_t CONNECTION_TIMEOUT_MS = 30000;
	if (millis() > gLastNavigationDataReceived_ms + CONNECTION_TIMEOUT_MS)
		Data::clearNavigationData();
	if (millis() > gLastSpeedDataReceived_ms + CONNECTION_TIMEOUT_MS)
		Data::clearSpeedData();

	if (!bt.connected()) {
		Data::clearNavigationData();
		Data::clearSpeedData();
	}

	const bool hasData = Data::hasNavigationData() || Data::hasSpeedData();

	static bool needBacklightOff = false;
	bool needBacklightOffNew = (!hasData || !bt.connected());
	if (needBacklightOff != needBacklightOffNew) {
		needBacklightOff = needBacklightOffNew;
		if (needBacklightOffNew) {
			BacklightControl::offWithTimer();
		} else {
			Pref::backlight ? BacklightControl::on() : BacklightControl::off();
		}
	}

	BacklightControl::update();

	// Start drawing
	lcd.fillScreen(BLACK);

	if (isOverspeed(Data::speed())) {
		DO_EVERY(10000) {
			BacklightControl::flashScreen();
		}
	}

	// Splash screen only
	if (!hasData) {
		drawSplashScreen();
	}
	// Speedometer only
	else if (!Data::hasNavigationData()) {
		drawSplashScreen("CatDrive", -33);
		lcd.setFont(&Org_01);
		lcd.setTextSize(4);
		lcd.setTextWrap(false);

		String txtSpeed(Data::speed());

		constexpr int16_t spacing = 22;
		const int16_t textWidth = txtSpeed.length() * spacing;

		static bool inverted = false;
		DO_EVERY(1000) {
			inverted = !inverted;
		}
		if (isOverspeed(Data::speed()) && inverted) {
			lcd.fillRect(lcd.width() - textWidth - 6, 12, textWidth + 12, 28, WHITE);
			drawTextAsMonospaced(lcd, lcd.width() - textWidth - 3, 32, spacing, txtSpeed, BLACK);
		} else {
			drawTextAsMonospaced(lcd, lcd.width() - textWidth - 3, 32, spacing, txtSpeed, WHITE);
		}
	}
	// All data
	else {
		UI::nextRoad.setText(Data::nextRoad());
		UI::nextRoad.update();

		UI::nextRoadDesc.setText(Data::nextRoadDesc());
		UI::nextRoadDesc.update();

		UI::eta.setText(Data::eta());
		UI::eta.update();

		UI::distance.setText(Data::distanceToNextTurn());
		UI::distance.update();

		// Turn icon
		lcd.drawBitmap(0, 0, Data::details::iconBuffer, 32, 32, WHITE);
		drawHashedVLine(lcd, 33, 0, 32);
		drawHashedHLine(lcd, 0, 31, lcd.width());

		const auto drawCanvas = [](Adafruit_GFX& gfx, const GFXcanvas1& canvas, int16_t x, int16_t y) {
			lcd.drawBitmap(x, y, canvas.getBuffer(), canvas.width(), canvas.height(), WHITE);
		};

		// Distance to next turn
		drawCanvas(lcd, UI::distance, 37, 4);
		// ETA
		drawCanvas(lcd, UI::eta, 38, 18);

		if (UI::nextRoadDesc.text().isEmpty()) {
			// Main road name
			drawCanvas(lcd, UI::nextRoad, 0, 38);
		} else {
			// Main road name
			drawCanvas(lcd, UI::nextRoad, 0, 32);
			// Sub road name
			drawCanvas(lcd, UI::nextRoadDesc, 0, 50);
		}

		lcd.setFont(&Org_01);
		lcd.setTextSize(2);
		lcd.setTextWrap(false);

		String txtSpeed(Data::speed());
		constexpr int16_t spacing = 12;
		const int16_t textWidth = txtSpeed.length() * spacing;

		static bool inverted = false;
		DO_EVERY(1000) {
			inverted = !inverted;
		}
		if (isOverspeed(Data::speed()) && inverted) {
			lcd.fillRect(lcd.width() - textWidth - 4, 0, textWidth + 3, 14, WHITE);
			drawTextAsMonospaced(lcd, lcd.width() - textWidth - 2, 10, spacing, txtSpeed, BLACK);
		} else {
			drawTextAsMonospaced(lcd, lcd.width() - textWidth - 2, 10, spacing, txtSpeed, WHITE);
		}
	}

	lcd.display();
	delay(50);
#endif
}
