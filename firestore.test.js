const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validExamPayload(uid, nowOffsetMs = -2000) {
  const ts = new Date(Date.now() + nowOffsetMs);
  return {
    userId: uid,
    title: "Organic Chemistry Midterm",
    subject: "Chemistry",
    difficulty: "Honors",
    teacherOverview: "Covers alkenes, stereochemistry, and reaction mechanisms.",
    pageCount: 2,
    objectiveCount: 60,
    theoryCount: 40,
    objectiveAnsweredCount: 0,
    objectiveCorrectCount: 0,
    theoryMasteredCount: 0,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validObjectiveQuestionPayload(uid, examId, num = 1, nowOffsetMs = -2000) {
  const ts = new Date(Date.now() + nowOffsetMs);
  return {
    userId: uid,
    examId,
    questionNumber: num,
    questionType: "OBJECTIVE",
    topicTag: "Alkenes",
    bloomLevel: "Understand",
    promptText: "Which reagent converts an alkene into a vicinal diol?",
    options: ["OsO4 / H2O2", "HBr", "NaBH4", "LiAlH4"],
    correctOptionIndex: 0,
    explanation: "Osmium tetroxide performs syn-dihydroxylation of alkenes.",
    marks: 1,
    markingPoints: [],
    modelAnswer: "",
    selectedOptionIndex: -1,
    studentTheoryAnswer: "",
    theoryGrade: "UNGRADED",
    createdAt: ts,
    updatedAt: ts,
  };
}

function validTheoryQuestionPayload(uid, examId, num = 61, nowOffsetMs = -2000) {
  const ts = new Date(Date.now() + nowOffsetMs);
  return {
    userId: uid,
    examId,
    questionNumber: num,
    questionType: "THEORY",
    topicTag: "Mechanisms",
    bloomLevel: "Analyze",
    promptText: "Compare SN1 and SN2 mechanisms with respect to stereochemistry and solvent.",
    options: [],
    correctOptionIndex: -1,
    explanation: "",
    marks: 10,
    markingPoints: [
      "SN1 proceeds via planar carbocation leading to racemization (3 marks)",
      "SN2 proceeds via backside attack causing inversion of configuration (3 marks)",
      "Polar protic favors SN1; polar aprotic favors SN2 (4 marks)",
    ],
    modelAnswer: "SN1 is a two-step unimolecular reaction...",
    selectedOptionIndex: -1,
    studentTheoryAnswer: "",
    theoryGrade: "UNGRADED",
    createdAt: ts,
    updatedAt: ts,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or write exams or questions", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("exams").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("exams").doc("exam_1").set(validExamPayload(ALICE_UID))
  );
});

test("Authenticated owner: atomic batch create of exam + objective & theory questions succeeds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const examRef = aliceDb.collection("users").doc(ALICE_UID).collection("exams").doc("exam_1");
  const q1Ref = examRef.collection("questions").doc("q_001");
  const q61Ref = examRef.collection("questions").doc("q_061");

  const batch = aliceDb.batch();
  batch.set(examRef, validExamPayload(ALICE_UID));
  batch.set(q1Ref, validObjectiveQuestionPayload(ALICE_UID, "exam_1", 1));
  batch.set(q61Ref, validTheoryQuestionPayload(ALICE_UID, "exam_1", 61));
  await assertSucceeds(batch.commit());

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("exams").get());
  await assertSucceeds(examRef.collection("questions").orderBy("questionNumber").get());
});

test("Authenticated owner: partial updates on exam progress and question answers succeed; immutable or ghost fields fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const examRef = aliceDb.collection("users").doc(ALICE_UID).collection("exams").doc("exam_1");
  const q1Ref = examRef.collection("questions").doc("q_001");
  const q61Ref = examRef.collection("questions").doc("q_061");

  const batch = aliceDb.batch();
  batch.set(examRef, validExamPayload(ALICE_UID));
  batch.set(q1Ref, validObjectiveQuestionPayload(ALICE_UID, "exam_1", 1));
  batch.set(q61Ref, validTheoryQuestionPayload(ALICE_UID, "exam_1", 61));
  await assertSucceeds(batch.commit());

  // Valid partial updates matching Kotlin ExamRepository
  await assertSucceeds(
    q1Ref.update({
      selectedOptionIndex: 0,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertSucceeds(
    q61Ref.update({
      studentTheoryAnswer: "SN1 gives racemic mixture while SN2 gives inversion.",
      theoryGrade: "FULL",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertSucceeds(
    examRef.update({
      objectiveAnsweredCount: 1,
      objectiveCorrectCount: 1,
      theoryMasteredCount: 1,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject shadow/ghost field
  await assertFails(
    examRef.update({
      objectiveAnsweredCount: 2,
      isAdminVerified: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject immutable createdAt mutation
  await assertFails(
    examRef.update({
      createdAt: new Date(Date.now() - 100),
      updatedAt: new Date(Date.now() - 100),
    })
  );

  // Reject future timestamp (> request.time)
  await assertFails(
    q1Ref.update({
      selectedOptionIndex: 1,
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Reject @DocumentId `id` field in create payload
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("exams").doc("exam_2").set({
      id: "exam_2",
      ...validExamPayload(ALICE_UID),
    })
  );
});

test("Cross-user isolation: Bob cannot read, write, or delete Alice's exams or questions", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(ALICE_UID).collection("exams").doc("exam_1").set(validExamPayload(ALICE_UID));
    await db.collection("users").doc(ALICE_UID).collection("exams").doc("exam_1")
      .collection("questions").doc("q_001").set(validObjectiveQuestionPayload(ALICE_UID, "exam_1", 1));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("exams").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("exams").doc("exam_1").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("exams").doc("exam_1").update({
      title: "Hijacked",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("exams").doc("exam_1")
      .collection("questions").doc("q_001").delete()
  );
});

test("Orphaned question write without parent exam fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("exams").doc("nonexistent_exam")
      .collection("questions").doc("q_001").set(validObjectiveQuestionPayload(ALICE_UID, "nonexistent_exam", 1))
  );
});
