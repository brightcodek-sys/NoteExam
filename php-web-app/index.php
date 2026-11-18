<?php
declare(strict_types=1);
/**
 * NoteExam PHP + Firebase Web Companion
 * Students can capture/upload note photos, generate 100 teacher-style
 * objective & theory questions via PHP backend, and sync exams to Cloud Firestore.
 */
?>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>NoteExam — PHP &amp; Firebase Study Question Generator</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=Playfair+Display:wght@600;700&family=Plus+Jakarta+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg: #FAF8F3; --card: #FFFFFF; --ink: #181A1F; --muted: #5A606D;
      --indigo: #1E3A5F; --teal: #0F766E; --amber: #D97706; --border: #E2DDD2;
    }
    * { box-sizing: border-box; }
    body { margin: 0; font-family: 'Plus Jakarta Sans', sans-serif; background: var(--bg); color: var(--ink); }
    header { background: var(--card); border-bottom: 1px solid var(--border); padding: 14px 24px; display: flex; justify-content: space-between; align-items: center; }
    h1, h2 { font-family: 'Playfair Display', serif; margin: 0; }
    .container { max-width: 980px; margin: 24px auto; padding: 0 16px; }
    .card { background: var(--card); border: 1px solid var(--border); border-radius: 16px; padding: 20px; margin-bottom: 20px; }
    .btn { background: var(--indigo); color: #fff; border: none; border-radius: 10px; padding: 12px 18px; font-weight: 600; cursor: pointer; }
    .btn-teal { background: var(--teal); }
    .btn:disabled { opacity: 0.6; cursor: not-allowed; }
    .row { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
    input[type="text"], select { padding: 10px 12px; border: 1px solid var(--border); border-radius: 10px; font-family: inherit; }
    .question-card { border: 1px solid var(--border); border-radius: 12px; padding: 14px; margin-top: 12px; background: #fff; }
    .badge { display: inline-block; padding: 3px 8px; border-radius: 6px; font-size: 12px; font-weight: 600; background: #D6E4F5; color: #0B1F38; }
    .hidden { display: none; }
  </style>
</head>
<body>
  <header>
    <div>
      <h1 style="font-size: 22px; color: var(--indigo);">NoteExam Web (PHP + Firebase)</h1>
      <small style="color: var(--muted);">Snap study notes &bull; Generate 100 Objective &amp; Theory questions</small>
    </div>
    <div id="auth-controls">
      <button id="btn-signin" class="btn">Sign in with Google</button>
      <span id="user-label" class="hidden" style="margin-right: 10px; font-weight: 600;"></span>
      <button id="btn-signout" class="btn hidden" style="background: var(--muted);">Sign out</button>
    </div>
  </header>

  <main class="container">
    <section class="card">
      <h2>1. Capture or Upload Study Notes</h2>
      <p style="color: var(--muted);">Take a photo with your device camera or upload note pages. Our PHP + Gemini teacher engine will create 100 objective &amp; theory questions.</p>
      <form id="note-form">
        <div class="row" style="margin-bottom: 12px;">
          <input id="note-files" type="file" name="notes[]" accept="image/*" capture="environment" multiple required />
          <input id="exam-title" type="text" name="title" placeholder="Optional Exam Title / Subject" style="flex: 1; min-width: 220px;" />
          <select id="mix-preset" name="mix">
            <option value="60_40">60 Objective / 40 Theory (100 Qs)</option>
            <option value="70_30">70 Objective / 30 Theory (100 Qs)</option>
            <option value="50_50">50 Objective / 50 Theory (100 Qs)</option>
          </select>
          <select id="difficulty" name="difficulty">
            <option value="Standard">Standard</option>
            <option value="Honors">Honors</option>
            <option value="Quick Review">Quick Review</option>
          </select>
        </div>
        <button id="btn-generate" type="submit" class="btn btn-teal">Generate 100 Exam Questions</button>
        <span id="status-text" style="margin-left: 12px; color: var(--muted);"></span>
      </form>
    </section>

    <section id="results-section" class="card hidden">
      <h2 id="result-title">Generated Exam</h2>
      <p id="result-overview" style="color: var(--muted);"></p>
      <div id="questions-container"></div>
    </section>
  </main>

  <script type="module">
    import { initializeApp } from "https://www.gstatic.com/firebasejs/11.6.0/firebase-app.js";
    import { getAuth, GoogleAuthProvider, signInWithPopup, signOut, onAuthStateChanged } from "https://www.gstatic.com/firebasejs/11.6.0/firebase-auth.js";
    import { getFirestore, collection, doc, writeBatch, serverTimestamp } from "https://www.gstatic.com/firebasejs/11.6.0/firebase-firestore.js";

    // Replace web apiKey & appId from your Firebase Console Web App settings for project gen-lang-client-0327524253
    const firebaseConfig = {
      apiKey: "YOUR_FIREBASE_WEB_API_KEY",
      authDomain: "gen-lang-client-0327524253.firebaseapp.com",
      projectId: "gen-lang-client-0327524253",
      appId: "YOUR_FIREBASE_WEB_APP_ID"
    };
    const DATABASE_ID = "ai-studio-android-noteexam-8251ffce-0afe-41ee-8048-98750fd15f4c";

    const app = initializeApp(firebaseConfig);
    const auth = getAuth(app);
    const db = getFirestore(app, DATABASE_ID);

    const btnSignIn = document.getElementById("btn-signin");
    const btnSignOut = document.getElementById("btn-signout");
    const userLabel = document.getElementById("user-label");
    const form = document.getElementById("note-form");
    const statusText = document.getElementById("status-text");

    let currentUser = null;

    onAuthStateChanged(auth, (user) => {
      currentUser = user;
      if (user) {
        btnSignIn.classList.add("hidden");
        btnSignOut.classList.remove("hidden");
        userLabel.classList.remove("hidden");
        userLabel.textContent = user.displayName || user.email;
      } else {
        btnSignIn.classList.remove("hidden");
        btnSignOut.classList.add("hidden");
        userLabel.classList.add("hidden");
      }
    });

    btnSignIn.addEventListener("click", async () => {
      await signInWithPopup(auth, new GoogleAuthProvider());
    });
    btnSignOut.addEventListener("click", async () => {
      await signOut(auth);
    });

    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      const files = document.getElementById("note-files").files;
      if (!files.length) return;

      const btn = document.getElementById("btn-generate");
      btn.disabled = true;
      statusText.textContent = "Teacher AI is analyzing your notes and writing 100 questions...";

      try {
        const fd = new FormData(form);
        const resp = await fetch("api/generate_questions.php", { method: "POST", body: fd });
        const data = await resp.json();
        if (!resp.ok || data.error) throw new Error(data.error || "Generation failed");

        renderExam(data);

        if (currentUser && firebaseConfig.apiKey !== "YOUR_FIREBASE_WEB_API_KEY") {
          await saveExamToFirestore(currentUser.uid, data, files.length);
          statusText.textContent = `Generated ${data.questions.length} questions and saved to Firestore!`;
        } else {
          statusText.textContent = `Generated ${data.questions.length} questions! (Sign in with Google & configure Web API key to sync to Firestore.)`;
        }
      } catch (err) {
        statusText.textContent = "Error: " + err.message;
      } finally {
        btn.disabled = false;
      }
    });

    async function saveExamToFirestore(uid, examData, pageCount) {
      const examRef = doc(collection(db, "users", uid, "exams"));
      const batch = writeBatch(db);
      const objCount = examData.questions.filter(q => q.questionType === "OBJECTIVE").length;
      const theoryCount = examData.questions.filter(q => q.questionType === "THEORY").length;

      batch.set(examRef, {
        userId: uid,
        title: examData.title,
        subject: examData.subject,
        difficulty: examData.difficulty,
        teacherOverview: examData.teacherOverview || "",
        pageCount,
        objectiveCount: objCount,
        theoryCount: theoryCount,
        objectiveAnsweredCount: 0,
        objectiveCorrectCount: 0,
        theoryMasteredCount: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp()
      });

      examData.questions.slice(0, 100).forEach((q, idx) => {
        const num = idx + 1;
        const qId = "q_" + String(num).padStart(3, "0");
        const qRef = doc(db, "users", uid, "exams", examRef.id, "questions", qId);
        const isObj = q.questionType === "OBJECTIVE";
        batch.set(qRef, {
          userId: uid,
          examId: examRef.id,
          questionNumber: num,
          questionType: q.questionType,
          topicTag: q.topicTag || "General",
          bloomLevel: q.bloomLevel || "Understand",
          promptText: q.promptText,
          options: isObj ? (q.options || []).slice(0, 4) : [],
          correctOptionIndex: isObj ? (q.correctOptionIndex ?? 0) : -1,
          explanation: isObj ? (q.explanation || "") : "",
          marks: q.marks || 1,
          markingPoints: isObj ? [] : (q.markingPoints || []).slice(0, 8),
          modelAnswer: isObj ? "" : (q.modelAnswer || ""),
          selectedOptionIndex: -1,
          studentTheoryAnswer: "",
          theoryGrade: "UNGRADED",
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp()
        });
      });

      await batch.commit();
    }

    function renderExam(data) {
      const section = document.getElementById("results-section");
      section.classList.remove("hidden");
      document.getElementById("result-title").textContent = `${data.title} (${data.questions.length} Questions)`;
      document.getElementById("result-overview").textContent = data.teacherOverview || "";
      const container = document.getElementById("questions-container");
      container.innerHTML = "";

      data.questions.forEach((q, i) => {
        const div = document.createElement("div");
        div.className = "question-card";
        const isObj = q.questionType === "OBJECTIVE";
        let body = `<div class="badge">Q${i + 1} &bull; ${q.questionType} &bull; ${escapeHtml(q.topicTag || "")}</div>`;
        body += `<p style="font-weight: 600;">${escapeHtml(q.promptText)}</p>`;
        if (isObj) {
          const letters = ["A", "B", "C", "D"];
          body += "<ul>" + (q.options || []).map((o, idx) => `<li><strong>${letters[idx]}:</strong> ${escapeHtml(o)}</li>`).join("") + "</ul>";
          body += `<details><summary style="cursor:pointer;color:var(--teal);">Teacher Answer &amp; Explanation</summary><p><strong>Correct: ${letters[q.correctOptionIndex] || "?"}</strong> — ${escapeHtml(q.explanation || "")}</p></details>`;
        } else {
          body += `<p style="color:var(--amber);font-weight:600;">[${q.marks || 5} marks]</p>`;
          body += `<details><summary style="cursor:pointer;color:var(--teal);">Reveal Marking Scheme &amp; Model Answer</summary><ul>${(q.markingPoints || []).map(p => `<li>${escapeHtml(p)}</li>`).join("")}</ul><p>${escapeHtml(q.modelAnswer || "")}</p></details>`;
        }
        div.innerHTML = body;
        container.appendChild(div);
      });
    }

    function escapeHtml(str) {
      return String(str).replace(/[&<>"']/g, s => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" }[s]));
    }
  </script>
</body>
</html>
