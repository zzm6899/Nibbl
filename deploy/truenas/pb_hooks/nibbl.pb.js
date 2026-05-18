routerAdd("POST", "/api/nibbl/ingest", (e) => {
  const expectedKey = $os.getenv("NIBBL_INGEST_KEY")
  const providedKey = e.request.header.get("X-Nibbl-Key")

  if (!expectedKey || providedKey !== expectedKey) {
    throw new ForbiddenError("Invalid ingest key")
  }

  const data = new DynamicModel({
    timestamp: 0,
    title: "",
    category: "",
    caffeineMg: null,
    cafe: "",
    locationName: "",
    latitude: null,
    longitude: null,
    friendNames: [],
    ownerId: "",
    ownerName: "",
    ownerTag: "",
  })
  e.bindBody(data)

  const collection = $app.findCollectionByNameOrId("logs")
  const record = new Record(collection)
  record.set("timestamp", data.timestamp)
  record.set("title", data.title)
  record.set("category", data.category || "drink")
  record.set("caffeineMg", data.caffeineMg)
  record.set("cafe", data.cafe)
  record.set("locationName", data.locationName)
  record.set("latitude", data.latitude)
  record.set("longitude", data.longitude)
  record.set("friendNames", data.friendNames || [])
  try {
    record.set("ownerId", data.ownerId || "")
    record.set("ownerName", data.ownerName || "")
    record.set("ownerTag", data.ownerTag || "")
  } catch (_) {}

  $app.save(record)

  return e.json(201, { id: record.id })
}, $apis.bodyLimit(64 * 1024))

routerAdd("GET", "/api/nibbl/stats", (e) => {
  const stats = {
    entries: 0,
    cafes: 0,
    friends: 0,
    categories: 0,
    status: "healthy",
  }

  try {
    const records = $app.findRecordsByFilter("logs", "", "-created", 500, 0)
    const cafes = {}
    const friends = {}
    const categories = {}

    stats.entries = records.length
    records.forEach((record) => {
      const cafe = String(record.get("cafe") || "").trim().toLowerCase()
      const category = String(record.get("category") || "").trim().toLowerCase()
      const friendNames = record.get("friendNames") || []

      if (cafe) cafes[cafe] = true
      if (category) categories[category] = true
      if (Array.isArray(friendNames)) {
        friendNames.forEach((friend) => {
          const name = String(friend || "").trim().toLowerCase()
          if (name) friends[name] = true
        })
      }
    })

    stats.cafes = Object.keys(cafes).length
    stats.friends = Object.keys(friends).length
    stats.categories = Object.keys(categories).length
  } catch (error) {
    stats.status = "setup"
  }

  return e.json(200, stats)
})

routerAdd("GET", "/api/nibbl/friends/available", (e) => {
  const rawTag = String(e.request.url.query().get("tag") || "")
  const tag = rawTag.trim().toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 10)

  if (!tag || tag.length < 3) {
    return e.json(200, { tag, available: false, reason: "too_short" })
  }

  try {
    const records = $app.findRecordsByFilter("logs", "", "-created", 500, 0)
    const taken = records.some((record) => {
      const ownerTag = String(record.get("ownerTag") || "").trim().toLowerCase()
      if (ownerTag === tag) return true

      const friendNames = record.get("friendNames") || []
      if (!Array.isArray(friendNames)) return false
      return friendNames.some((friend) => {
        return String(friend || "").trim().toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 10) === tag
      })
    })

    return e.json(200, { tag, available: !taken })
  } catch (error) {
    return e.json(200, { tag, available: true, status: "setup" })
  }
})

routerAdd("POST", "/api/nibbl/profile", (e) => {
  requireIngestKey(e)

  const data = new DynamicModel({
    ownerId: "",
    ownerName: "",
    ownerTag: "",
  })
  e.bindBody(data)

  const ownerId = String(data.ownerId || "").trim().slice(0, 64)
  const ownerName = String(data.ownerName || "").trim().slice(0, 48)
  const ownerTag = String(data.ownerTag || "").trim().toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 10)

  if (!ownerId) return e.json(400, { error: "ownerId_required" })

  try {
    const records = $app.findRecordsByFilter("logs", `ownerId = "${ownerId.replace(/"/g, '\\"')}"`, "-created", 500, 0)
    records.forEach((record) => {
      try {
        record.set("ownerName", ownerName)
        record.set("ownerTag", ownerTag)
        $app.save(record)
      } catch (_) {}
    })
    return e.json(200, { updated: records.length })
  } catch (error) {
    return e.json(200, { updated: 0, status: "setup" })
  }
})
