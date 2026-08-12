local Validation = {}

local function isFinite(value)
    return type(value) == "number"
        and value == value
        and value ~= math.huge
        and value ~= -math.huge
end

local function isCoordinate(value)
    return isFinite(value) and value >= -300000 and value <= 300000
end

local function isFloor(value)
    return isFinite(value) and value >= -32 and value <= 32 and value == math.floor(value)
end

function Validation.player(snapshot, observed)
    if type(snapshot) ~= "table" or type(observed) ~= "table" then
        return false, "snapshot"
    end

    if type(snapshot.id) ~= "string" or snapshot.id == "" or snapshot.id ~= observed.id then
        return false, "identity"
    end

    if not isCoordinate(snapshot.x) or not isCoordinate(snapshot.y) then
        return false, "coordinate"
    end

    if not isFloor(snapshot.z) then
        return false, "floor"
    end

    if not isCoordinate(observed.x) or not isCoordinate(observed.y) or not isFloor(observed.z) then
        return false, "observed"
    end

    if math.abs(snapshot.z - observed.z) > 1 then
        return false, "jump"
    end

    local dx = snapshot.x - observed.x
    local dy = snapshot.y - observed.y
    if dx * dx + dy * dy > 625 then
        return false, "jump"
    end

    return true, "ok"
end

function Validation.nativeAssistConfig(config)
    if type(config) ~= "table" then return false, "native-config" end
    local booleans = {
        "nativeAssistEnabled", "serverRewindEnabled", "pvpRewind", "pveRewind",
        "playerNativeAssist", "zombieCombatBubble", "vehicleNativeAssist",
        "directPlayerCorrection", "directVehicleCorrection"
    }
    for _, key in ipairs(booleans) do
        if type(config[key]) ~= "boolean" then return false, "native-config" end
    end
    if config.directPlayerCorrection or config.directVehicleCorrection then
        return false, "native-direct-correction"
    end
    if not isFinite(config.maxRewindMs) or config.maxRewindMs < 0
        or config.maxRewindMs > 150 or config.maxRewindMs ~= math.floor(config.maxRewindMs) then
        return false, "native-rewind-cap"
    end
    local immutable = {
        historyMs = 750,
        combatSampleMs = 100,
        hardMaxRewindMs = 200,
        rttCutoffMs = 300,
        jitterCutoffMs = 50,
        rangeEpsilonTiles = 0.25,
        divergenceRejectTiles = 2.5,
        combatBubbleInnerRadius = 8,
        combatBubbleOuterRadius = 15,
        combatBubbleMaxPlayers = 12,
        combatBubbleMaxZombies = 48,
        diagnosticsIntervalSeconds = 60
    }
    for key, expected in pairs(immutable) do
        if config[key] ~= expected then return false, "native-config-mismatch" end
    end
    return true, "ok"
end

return Validation
