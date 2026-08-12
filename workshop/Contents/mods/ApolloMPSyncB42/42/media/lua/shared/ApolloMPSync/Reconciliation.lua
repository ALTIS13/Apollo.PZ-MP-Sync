local Reconciliation = {}

local DEFAULT_THRESHOLD = 2.5
local DEFAULT_CONFIRMATIONS = 4
local DEFAULT_MAX_STEP = 0.25
local DEFAULT_BASE_COOLDOWN_MS = 600
local DEFAULT_MAX_PLAUSIBLE_DISTANCE = 25

local floorActions = {
    ["climb-window"] = true,
    ["climb-fence"] = true,
    ["climb-ladder"] = true,
    ["floor-transition"] = true
}

local function isFinite(value)
    return type(value) == "number"
        and value == value
        and value ~= math.huge
        and value ~= -math.huge
end

local function positive(config, primary, alternate, fallback)
    local value = config[primary]
    if value == nil and alternate ~= nil then
        value = config[alternate]
    end

    if isFinite(value) and value > 0 then
        return value
    end

    return fallback
end

local function nonNegative(value)
    if isFinite(value) and value >= 0 then
        return value
    end

    return 0
end

local function latencyMs(localView, report, config)
    if report.latencyMs ~= nil then
        return nonNegative(report.latencyMs)
    end

    if report.pingMs ~= nil then
        return nonNegative(report.pingMs)
    end

    if localView.latencyMs ~= nil then
        return nonNegative(localView.latencyMs)
    end

    if localView.pingMs ~= nil then
        return nonNegative(localView.pingMs)
    end

    if config.latencyMs ~= nil then
        return nonNegative(config.latencyMs)
    end

    return nonNegative(config.pingMs)
end

local function resetMovement(tracker)
    tracker.confirmations = 0
    tracker.dx = nil
    tracker.dy = nil
end

local function resetTracker(state, id)
    state.players[id] = nil
end

local function none()
    return { kind = "none" }
end

function Reconciliation.new()
    return { players = {} }
end

function Reconciliation.evaluate(state, localView, report, nowMs, config)
    if type(state) ~= "table" or type(localView) ~= "table" or type(report) ~= "table" then
        return none()
    end

    config = type(config) == "table" and config or {}

    if report.id == nil or report.id == localView.id then
        return none()
    end

    if not isFinite(localView.x) or not isFinite(localView.y)
        or not isFinite(report.x) or not isFinite(report.y) then
        return none()
    end

    if type(state.players) ~= "table" then
        state.players = {}
    end

    local dx = report.x - localView.x
    local dy = report.y - localView.y
    local distance = math.sqrt(dx * dx + dy * dy)
    local plausible = positive(config, "maxPlausibleDistance", nil, DEFAULT_MAX_PLAUSIBLE_DISTANCE)

    if report.cellLoaded == false or distance > plausible then
        resetTracker(state, report.id)
        return { kind = "vanilla-reset" }
    end

    local tracker = state.players[report.id]
    if tracker == nil then
        tracker = { confirmations = 0 }
        state.players[report.id] = tracker
    end

    if report.z ~= localView.z then
        if floorActions[report.action] and tracker.floor == report.z then
            resetMovement(tracker)
            tracker.floor = nil
            return { kind = "floor-transition" }
        end

        if floorActions[report.action] then
            tracker.floor = report.z
        else
            tracker.floor = nil
        end
        resetMovement(tracker)
        return none()
    end

    tracker.floor = nil

    local threshold = positive(config, "softThreshold", "threshold", DEFAULT_THRESHOLD)
    if distance < threshold then
        resetMovement(tracker)
        return none()
    end

    if tracker.dx ~= nil and tracker.dx * dx + tracker.dy * dy < 0 then
        resetMovement(tracker)
    end

    tracker.dx = dx
    tracker.dy = dy
    tracker.confirmations = tracker.confirmations + 1

    local needed = positive(config, "confirmations", nil, DEFAULT_CONFIRMATIONS)
    needed = math.ceil(needed)
    if tracker.confirmations < needed then
        return none()
    end

    local baseCooldown = positive(config, "baseCooldownMs", "cooldownMs", DEFAULT_BASE_COOLDOWN_MS)
    local cooldown = baseCooldown + latencyMs(localView, report, config)
    local timestamp = isFinite(nowMs) and nowMs or 0
    if tracker.lastCorrectionMs ~= nil and timestamp - tracker.lastCorrectionMs < cooldown then
        return none()
    end

    local maxStep = positive(config, "maxStep", "maxCorrectionStep", DEFAULT_MAX_STEP)
    local scale = maxStep / distance
    resetMovement(tracker)
    tracker.lastCorrectionMs = timestamp
    return { kind = "soft", dx = dx * scale, dy = dy * scale }
end

return Reconciliation
