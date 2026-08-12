local ActionRegistry = require("ApolloMPSync/ActionRegistry")

local ActionPolicy = {}

local allowedPhases = {
    start = true,
    progress = true,
    complete = true,
    cancel = true
}

local forbiddenPayloadKeys = {
    lua = true,
    inventory = true,
    xp = true,
    damage = true,
    item = true,
    recipe = true
}

local function isFinite(value)
    return type(value) == "number"
        and value == value
        and value ~= math.huge
        and value ~= -math.huge
end

local function positiveConfig(config, key, fallback, minimum)
    local value = config[key]
    if isFinite(value) and value >= minimum then
        return value
    end

    return fallback
end

local function sanitizeAnchor(anchor)
    if type(anchor) ~= "table"
        or not isFinite(anchor.x) or anchor.x < -300000 or anchor.x > 300000
        or not isFinite(anchor.y) or anchor.y < -300000 or anchor.y > 300000
        or not isFinite(anchor.z) or anchor.z ~= math.floor(anchor.z) or anchor.z < -32 or anchor.z > 32
        or not isFinite(anchor.facing) then
        return nil
    end

    return { x = anchor.x, y = anchor.y, z = anchor.z, facing = anchor.facing }
end

local function safeAnimationValue(value)
    if type(value) == "boolean" then
        return true
    end

    if isFinite(value) then
        return true
    end

    return type(value) == "string" and #value <= 64
end

local function sanitizeAnimationVars(descriptor, values)
    local allowed = {}
    for index = 1, #descriptor.animationVars do
        allowed[descriptor.animationVars[index]] = true
    end

    local sanitized = {}
    if type(values) ~= "table" then
        return sanitized
    end

    for key, value in pairs(values) do
        if allowed[key] and safeAnimationValue(value) then
            sanitized[key] = value
        end
    end

    return sanitized
end

local function hasForbiddenPayload(payload)
    if payload == nil then
        return false
    end

    if type(payload) ~= "table" then
        return true
    end

    for key in pairs(payload) do
        if forbiddenPayloadKeys[key] then
            return true
        end
    end

    return false
end

local function hasForbiddenGameplayField(snapshot)
    if hasForbiddenPayload(snapshot.payload) then
        return true
    end

    for key in pairs(forbiddenPayloadKeys) do
        if snapshot[key] ~= nil then
            return true
        end
    end

    return false
end

local function categoryEnabled(config, category)
    if config.actionEnabled == false or config.actionSyncEnabled == false then
        return false
    end

    if category == "sleep" and config.sleepEnabled == false then
        return false
    end

    return type(config.categoryEnabled) ~= "table" or config.categoryEnabled[category] ~= false
end

local function validSequence(sequence)
    return type(sequence) == "number"
        and sequence == math.floor(sequence)
        and sequence >= 0
        and sequence <= 2147483647
end

function ActionPolicy.accept(state, snapshot, nowMs, config)
    if type(state) ~= "table" or type(snapshot) ~= "table" then
        return nil, "snapshot"
    end

    if not isFinite(nowMs) then
        return nil, "time"
    end

    config = type(config) == "table" and config or {}

    if not allowedPhases[snapshot.phase] then
        return nil, "phase"
    end

    if hasForbiddenGameplayField(snapshot) then
        return nil, "payload"
    end

    if snapshot.phase == "progress" and snapshot.progress == nil then
        return nil, "progress"
    end

    if snapshot.progress ~= nil and (not isFinite(snapshot.progress)
        or snapshot.progress < 0 or snapshot.progress > 1) then
        return nil, "progress"
    end

    if snapshot.sequence ~= nil and not validSequence(snapshot.sequence) then
        return nil, "sequence"
    end

    local descriptor = ActionRegistry.resolve(snapshot.className)
    if not categoryEnabled(config, descriptor.category) then
        return nil, "disabled"
    end

    local anchor = sanitizeAnchor(snapshot.anchor)
    if anchor == nil then
        return nil, "anchor"
    end

    if snapshot.phase == "progress" then
        if state.activeKey ~= descriptor.key then
            return nil, "lifecycle"
        end

        local interval = positiveConfig(config, "progressIntervalMs", 500, 100)
        if nowMs - state.lastProgressMs < interval then
            return nil, "throttled"
        end
        state.lastProgressMs = nowMs
    elseif snapshot.phase == "start" then
        if state.activeKey ~= nil then
            return nil, "lifecycle"
        end
        state.activeKey = descriptor.key
        state.lastProgressMs = nowMs
        state.graceUntilMs = nil
    else
        if state.activeKey ~= descriptor.key then
            return nil, "lifecycle"
        end
        state.activeKey = nil
        state.lastProgressMs = nil
        state.graceUntilMs = nowMs + positiveConfig(config, "endGraceMs", 1250, 0)
    end

    local event = {
        key = descriptor.key,
        category = descriptor.category,
        phase = snapshot.phase,
        anchor = anchor,
        animationVars = sanitizeAnimationVars(descriptor, snapshot.animationVars),
        timestampMs = nowMs
    }

    if snapshot.progress ~= nil then
        event.progress = snapshot.progress
    end

    if snapshot.sequence ~= nil then
        event.sequence = snapshot.sequence
    end

    if snapshot.phase == "complete" or snapshot.phase == "cancel" then
        event.graceUntilMs = state.graceUntilMs
    end

    return event, "ok"
end

return ActionPolicy
