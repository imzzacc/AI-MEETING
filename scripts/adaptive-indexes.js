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
