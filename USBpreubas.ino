#include <Wire.h>
#include <U8g2lib.h>

// Ajustar pines I2C según la conexión física (ejemplo: SCL=14, SDA=12)
U8G2_SSD1306_128X64_NONAME_F_SW_I2C u8g2(U8G2_R0, /* clock=*/ 14, /* data=*/ 12, /* reset=*/ U8X8_PIN_NONE);

// Variables para manejo de mensajes
String displayMessage = "Esperando conexion...";
String lastCommand = "";
unsigned long lastMsgTime = 0;
bool connectionActive = false;

// Temporizador para animación
unsigned long lastAnimTime = 0;
int animDots = 0;

void setup() {
  Serial.begin(115200);
  Serial.setTimeout(50);
  
  u8g2.begin();
  u8g2.setFont(u8g2_font_ncenB08_tr);
  updateDisplay(); // Mostrar mensaje inicial inmediatamente
  Serial.println("INFO: ESP8266 Display iniciado");
}

void loop() {
  if (Serial.available() > 0) {
    String input = Serial.readStringUntil('\n');
    input.trim();
    lastMsgTime = millis();
    connectionActive = true;
    processSerialCommand(input);
  }
  
  unsigned long currentTime = millis();
  if (currentTime - lastAnimTime > 500) {
    lastAnimTime = currentTime;
    
    // Si pasan 5 segundos sin mensajes, volver a estado de espera
    if (currentTime - lastMsgTime > 5000 && connectionActive) {
      connectionActive = false;
      displayMessage = "Esperando conexion...";
      updateDisplay();
    }
    
    // Animación solo en modo espera
    if (!connectionActive) {
      animDots = (animDots + 1) % 4;
      updateDisplay();
    }
  }
}

void processSerialCommand(String command) {
  lastCommand = command;
  Serial.print("ECHO:");
  Serial.println(command);
  
  if (command.startsWith("MSG:")) {
    displayMessage = command.substring(4);
    updateDisplay();
    Serial.println("OK:Mensaje actualizado");
  }
  else if (command == "INIT") {
    displayMessage = "Conectado a Android";
    updateDisplay();
    Serial.println("OK:Inicializado");
  }
  else if (command == "CLEAR") {
    displayMessage = "";
    updateDisplay();
    Serial.println("OK:Pantalla limpia");
  }
  else {
    displayMessage = command;
    updateDisplay();
  }
}

void updateDisplay() {
  u8g2.clearBuffer();
  
  // Línea superior: Estado de conexión
  if (connectionActive) {
    u8g2.drawStr(0, 10, "[Conectado]");
  } else {
    u8g2.drawStr(0, 10, "Esperando");
    for (int i = 0; i < animDots; i++) {
      u8g2.drawStr(70 + i * 8, 10, ".");
    }
  }
  
  // Mensaje principal (centrado)
  u8g2.setFont(u8g2_font_ncenB10_tr);
  int msgWidth = u8g2.getStrWidth(displayMessage.c_str());
  int xPos = (128 - msgWidth) / 2;
  u8g2.setCursor(xPos > 0 ? xPos : 0, 40);
  u8g2.print(displayMessage);
  
  // Último comando recibido
  u8g2.setFont(u8g2_font_5x7_tr);
  u8g2.setCursor(0, 60);
  u8g2.print("Ultimo: " + lastCommand);
  
  u8g2.sendBuffer();
}