#include <ESP8266WiFi.h>
#include <PubSubClient.h>
#include <DHT.h>
#include <ArduinoJson.h>

// ===== CẤU HÌNH WIFI & MQTT =====
const char* ssid = "galaxy";
const char* password = "10281005";
const char* mqtt_server = "10.237.204.65";


// const char* ssid = "TP-Link_A6B4";
// const char* password = "25469479";
// const char* mqtt_server = "192.168.0.103"; // Điền IP laptop dải 192.168.0.x của mình


const int mqtt_port = 1883;
const char* mqtt_user = "datnguyen";
const char* mqtt_pass = "123456";

// ===== CẤU HÌNH CHÂN THIẾT BỊ & CẢM BIẾN =====
#define DHTPIN D4
#define DHTTYPE DHT11
#define LIGHT_PIN A0

#define LED_PIN D1   // Đèn LED
#define FAN_PIN D2   // Quạt
#define AC_PIN  D5   // Điều hoà

DHT dht(DHTPIN, DHTTYPE);
WiFiClient espClient;
PubSubClient client(espClient);

unsigned long lastMsg = 0;

void setup_wifi() {
  delay(10);
  Serial.println();
  Serial.print("Dang ket noi WiFi: ");
  Serial.println(ssid);

  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nWiFi da ket noi!");
  Serial.print("IP ESP8266: ");
  Serial.println(WiFi.localIP());
}

// Xử lý lệnh nhận từ topic "iot/control"
void callback(char* topic, byte* payload, unsigned int length) {
  String messageTemp = "";
  for (unsigned int i = 0; i < length; i++) {
    messageTemp += (char)payload[i];
  }
  Serial.print("Nhan lenh: ");
  Serial.println(messageTemp);

  // command_id là History.id; giữ hỗ trợ lệnh cũ chưa có ID.
  // {"device":"led"|"fan"|"ac", "cmd":"ON"|"OFF", "command_id":12}
  StaticJsonDocument<200> doc;
  DeserializationError error = deserializeJson(doc, messageTemp);
  if (!error) {
    String device = doc["device"].as<String>();
    String cmd = doc["cmd"].as<String>();
    if (cmd != "ON" && cmd != "OFF") {
      Serial.println("Bo qua lenh co cmd khong hop le");
      return;
    }
    bool hasCommandId = doc.containsKey("command_id");
    if (hasCommandId && (!doc["command_id"].is<int>() || doc["command_id"].as<int>() <= 0)) {
      Serial.println("Bo qua lenh co command_id khong hop le");
      return;
    }
    int state = (cmd == "ON") ? HIGH : LOW;

    if (device == "led") {
      digitalWrite(LED_PIN, state);
    } else if (device == "fan") {
      digitalWrite(FAN_PIN, state);
    } else if (device == "ac") {
      digitalWrite(AC_PIN, state);
    } else {
      Serial.println("Bo qua lenh co device khong hop le");
      return;
    }

    StaticJsonDocument<200> ack;
    ack["device"] = device;
    ack["status"] = cmd; // ON/OFF vẫn là trạng thái thiết bị, không phải kết quả lệnh trong DB.
    if (hasCommandId) {
      ack["command_id"] = doc["command_id"].as<int>();
    }
    char buffer[256];
    serializeJson(ack, buffer);
    if (!client.publish("iot/status", buffer, false)) {
      Serial.println("Khong gui duoc ACK MQTT");
    }
  }
}

void reconnect() {
  while (!client.connected()) {
    Serial.print("Dang ket noi MQTT Broker...");
    String clientId = "ESP8266Client-" + String(random(0xffff), HEX);
    if (client.connect(clientId.c_str(), mqtt_user, mqtt_pass)) {
      Serial.println(" Ket noi thanh cong!");
      client.subscribe("iot/control");
    } else {
      Serial.print(" That bai, rc=");
      Serial.print(client.state());
      Serial.println(" Thu lai sau 5s...");
      delay(5000);
    }
  }
}

void setup() {
  Serial.begin(115200);

  pinMode(LED_PIN, OUTPUT);
  pinMode(FAN_PIN, OUTPUT);
  pinMode(AC_PIN, OUTPUT);

  digitalWrite(LED_PIN, LOW);
  digitalWrite(FAN_PIN, LOW);
  digitalWrite(AC_PIN, LOW);

  dht.begin();
  setup_wifi();
  client.setServer(mqtt_server, mqtt_port);
  client.setCallback(callback);
}

void loop() {
  if (!client.connected()) {
    reconnect();
  }
  client.loop();

  // Đọc và gửi dữ liệu cảm biến định kỳ mỗi 2 giây
  unsigned long now = millis();
  if (now - lastMsg > 2000) {
    lastMsg = now;

    float h = dht.readHumidity();
    float t = dht.readTemperature();
    int lightRaw = analogRead(LIGHT_PIN);
    int lightVal = map(lightRaw, 0, 1023, 0, 1000);

    if (!isnan(h) && !isnan(t)) {
      StaticJsonDocument<200> doc;
      doc["temperature"] = t;
      doc["humidity"] = h;
      doc["light"] = lightVal;

      char buffer[256];
      serializeJson(doc, buffer);

      Serial.print("Gui du lieu: ");
      Serial.println(buffer);
      client.publish("iot/sensor/data", buffer);
    }
  }
}
