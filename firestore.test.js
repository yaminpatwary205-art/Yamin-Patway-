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

test("Unauthenticated user cannot read or write user data or subcollections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("books").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("notes").doc("note_1").set({
      userId: ALICE_UID,
      bookId: "book_1",
      chapterId: "chap_1",
      content: "Test",
      starred: false,
      overrideEnabled: -1,
      overrideIntervalMinutes: 0,
      revisionCount: 0,
      lastRevisedEpochMs: 0,
      lastRevisedDay: "",
      updatedAtEpochMs: 1000,
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("User profile create, partial update, and cross-user isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.com",
      themeMode: "system",
      soundEnabled: true,
      alarmVolume: 0.8,
      vibrationEnabled: true,
      defaultSnoozeMinutes: 5,
      vocabOrderMode: "ordered",
      streakCount: 1,
      lastActiveDay: "2026-11-18",
      todayRevisionCount: 2,
      todayDate: "2026-11-18",
      updatedAtEpochMs: 1000,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Partial update allowed fields
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      todayRevisionCount: 3,
      updatedAtEpochMs: 2000,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject immutable createdAt mutation
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost field / shadow update
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Cross-user isolation
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).update({ todayRevisionCount: 99 }));
});

test("Books, chapters, and notes CRUD + cross-user denial + validation bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("books").doc("book_bangla_1").set({
      userId: ALICE_UID,
      title: "বাংলা প্রথম পত্র",
      colorHex: "#E11D48",
      iconKey: "menu_book",
      sortOrder: 0,
      enabled: true,
      intervalMinutes: 30,
      useActiveHours: true,
      activeStartMinutes: 480,
      activeEndMinutes: 1320,
      examDateEpochMs: 0,
      updatedAtEpochMs: 1000,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("chapters").doc("chap_1").set({
      userId: ALICE_UID,
      bookId: "book_bangla_1",
      title: "অধ্যায় ১: শুভা",
      sortOrder: 0,
      overrideEnabled: -1,
      overrideIntervalMinutes: 0,
      useActiveHours: -1,
      activeStartMinutes: 480,
      activeEndMinutes: 1320,
      updatedAtEpochMs: 1000,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("notes").doc("note_1").set({
      userId: ALICE_UID,
      bookId: "book_bangla_1",
      chapterId: "chap_1",
      content: "রবীন্দ্রনাথ ঠাকুর ১৮৬১ সালে জন্মগ্রহণ করেন।",
      starred: true,
      overrideEnabled: 1,
      overrideIntervalMinutes: 15,
      revisionCount: 0,
      lastRevisedEpochMs: 0,
      lastRevisedDay: "",
      updatedAtEpochMs: 1000,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // List queries succeed for owner on empty or populated subcollections
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("notes").get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("paragraphs").get());

  // Cross-user read/write blocked
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("notes").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("notes").doc("note_1").get());

  // Reject extra @DocumentId 'id' field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("notes").doc("note_2").set({
      id: "note_2",
      userId: ALICE_UID,
      bookId: "book_bangla_1",
      chapterId: "chap_1",
      content: "Invalid with id field",
      starred: false,
      overrideEnabled: -1,
      overrideIntervalMinutes: 0,
      revisionCount: 0,
      lastRevisedEpochMs: 0,
      lastRevisedDay: "",
      updatedAtEpochMs: 1000,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Reject future timestamp
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("notes").doc("note_3").set({
      userId: ALICE_UID,
      bookId: "book_bangla_1",
      chapterId: "chap_1",
      content: "Future timestamp",
      starred: false,
      overrideEnabled: -1,
      overrideIntervalMinutes: 0,
      revisionCount: 0,
      lastRevisedEpochMs: 0,
      lastRevisedDay: "",
      updatedAtEpochMs: 1000,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );
});

test("Paragraphs, vocab lists, vocab words, weak words, and translations succeed for owner", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("paragraphs").doc("para_1").set({
      userId: ALICE_UID,
      title: "A Winter Morning",
      rawText: "A winter morning is misty and cold. Dew drops glisten on the grass.",
      lines: ["A winter morning is misty and cold.", "Dew drops glisten on the grass."],
      currentLineIndex: 0,
      enabled: true,
      intervalMinutes: 15,
      memoryMode: false,
      lastTriggeredEpochMs: 0,
      updatedAtEpochMs: 1000,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("vocab_lists").doc("list_1").set({
      userId: ALICE_UID,
      title: "Unit 1 Words",
      enabled: true,
      intervalMinutes: 15,
      reverseMode: false,
      lastTriggeredEpochMs: 0,
      currentIndex: 0,
      updatedAtEpochMs: 1000,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("vocab_words").doc("word_1").set({
      userId: ALICE_UID,
      listId: "list_1",
      word: "resilient",
      meaningBn: "স্থিতিস্থাপক / দৃঢ়চেতা",
      enabled: true,
      mastered: false,
      weakCount: 0,
      practiceCount: 0,
      lastPracticedEpochMs: 0,
      updatedAtEpochMs: 1000,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("weak_words").doc("weak_resilient").set({
      userId: ALICE_UID,
      word: "resilient",
      meaningBn: "দৃঢ়চেতা",
      sourceType: "vocab",
      mistakeCount: 1,
      lastMistakeEpochMs: 1000,
      updatedAtEpochMs: 1000,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("translations").doc("tr_1").set({
      userId: ALICE_UID,
      sourceText: "resilient",
      sourceLang: "en",
      targetLang: "bn",
      translatedText: "দৃঢ়চেতা",
      updatedAtEpochMs: 1000,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});
