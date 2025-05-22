# 🧠 IntelliJ Cursor Code Suggestion Plugin

A live code suggestion plugin for IntelliJ that mimics a “cursor-like” experience — powered by AI.

This plugin reads the current file you’re working on and uses AI to **predict the next 10 lines of code**, 

helping you code faster without breaking your flow.

Check the description below to access the Github link.

---

## 🚀 How to Run

Make sure you have [IntelliJ IDEA](https://www.jetbrains.com/idea/) and JDK 17+ installed.

```bash
./gradlew runIde
```

This will launch a new instance of IntelliJ with the plugin enabled for testing.

---

## ⚙️ Tech Stack

- Kotlin
- IntelliJ Platform Plugin SDK
- OpenAI API

---

## ✨ Features

- **Live AI Suggestions**  
  Predicts next 10 lines of code based on current document context.

- **Apply Suggestion with Ease**
    - Press `Tab` or `Enter` to apply suggestion.
    - Press any other key to continue editing and reject the suggestion.

- **Non-Intrusive Design**  
  Suggestions appear inline as hints — no popup windows or blocking modals.