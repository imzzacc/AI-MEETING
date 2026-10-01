// Run from the repository root with mongosh connected to the intended database.
// AI_MEETING_MIGRATION_DB must explicitly match that database. No personal data is read.
const expectedDatabase = process.env.AI_MEETING_MIGRATION_DB;
if (!expectedDatabase || db.getName() !== expectedDatabase) {
  throw new Error("Set AI_MEETING_MIGRATION_DB to the selected database before running this migration");
}
const indexes = JSON.parse(require("fs").readFileSync("scripts/adaptive-indexes.json", "utf8"));
for (const [collection, definitions] of Object.entries(indexes)) {
  for (const definition of definitions) {
    db.getCollection(collection).createIndex(definition.keys, {
      name: definition.name, unique: definition.unique === true,
    });
  }
  print(collection + ": " + db.getCollection(collection).getIndexes().map(index => index.name).join(", "));
}
// _id already enforces the scope-aware identity hash. The initial compound unique index
// omitted catalog/role/version scope and incorrectly blocked separate scoped mistakes.
const mistakes = db.getCollection("interview_adaptive_mistake");
const obsolete = mistakes.getIndexes().find(index => index.name === "adaptive_mistake_identity");
if (obsolete) {
  const expectedKeys = { userId: 1, knowledgePointId: 1, gapKey: 1, type: 1 };
  if (obsolete.unique !== true || JSON.stringify(obsolete.key) !== JSON.stringify(expectedKeys)) {
    throw new Error("Unexpected adaptive_mistake_identity definition; inspect before migration");
  }
  mistakes.dropIndex(obsolete.name);
  print("Removed obsolete unscoped unique index; scope-aware _id uniqueness remains enforced");
}
