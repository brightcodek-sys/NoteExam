# NoteExam — PHP + Firebase Web Companion

This directory contains the companion PHP web application matching the **NoteExam** Android app's Firestore schema and security rules (`firestore.rules`).

## Setup

1. Export your Gemini API key in your PHP server environment (never hardcode keys in source files):
   ```bash
   export GEMINI_API_KEY="your-gemini-api-key"
   ```
2. In `index.php`, update `firebaseConfig.apiKey` and `firebaseConfig.appId` with your Web App credentials from the Firebase Console for project `gen-lang-client-0327524253` (database ID `ai-studio-android-noteexam-8251ffce-0afe-41ee-8048-98750fd15f4c`).
3. Run PHP's built-in development server from this directory:
   ```bash
   php -S 127.0.0.1:8000
   ```
4. Open `http://127.0.0.1:8000` in your browser, sign in with Google, snap or upload pictures of your notes, and generate 100 objective & theory questions.
