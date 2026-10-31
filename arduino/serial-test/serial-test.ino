// Test sketch for the Arducam GUI.
// Prints a numbered line every second. Baud rate must match the GUI (9600).

unsigned long counter = 0;

void setup() {
  Serial.begin(9600);
  while (!Serial) { }
  Serial.println("Arducam serial test ready");
}

void loop() {
  Serial.print("Hello from Arduino #");
  Serial.println(counter++);
  delay(1000);
}
