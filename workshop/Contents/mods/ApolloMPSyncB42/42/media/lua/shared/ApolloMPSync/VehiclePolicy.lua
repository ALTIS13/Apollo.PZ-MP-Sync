local VehiclePolicy = {}

local function isFinite(value)
    return type(value) == "number"
        and value == value
        and value ~= math.huge
        and value ~= -math.huge
end

local function clamp(value, lower, upper, fallback)
    if not isFinite(value) then
        return fallback
    end

    if value < lower then
        return lower
    end

    if value > upper then
        return upper
    end

    return value
end

local function nonEmptyString(value)
    return type(value) == "string" and value ~= ""
end

function VehiclePolicy.sanitizeTransform(value)
    value = type(value) == "table" and value or {}

    return {
        x = clamp(value.x, -300000, 300000, 0),
        y = clamp(value.y, -300000, 300000, 0),
        z = clamp(value.z, -32, 32, 0),
        angle = clamp(value.angle, -360, 360, 0),
        vx = clamp(value.vx, -1000, 1000, 0),
        vy = clamp(value.vy, -1000, 1000, 0),
        angularVelocity = clamp(value.angularVelocity, -720, 720, 0),
        moving = value.moving == true
    }
end

local function positiveInteger(value)
    return isFinite(value) and value >= 0 and value == math.floor(value)
end

local function decision(kind, reason, target, assist)
    local value = { kind = kind, reason = reason, assist = assist }
    if target ~= nil then
        value.target = target
    end
    return value
end

local function matching(previous, current)
    return previous ~= nil
        and math.abs(previous.x - current.x) <= 0.01
        and math.abs(previous.y - current.y) <= 0.01
        and math.abs(previous.z - current.z) <= 0.01
        and math.abs(previous.angle - current.angle) <= 0.01
        and math.abs(previous.vx - current.vx) <= 0.01
        and math.abs(previous.vy - current.vy) <= 0.01
        and math.abs(previous.angularVelocity - current.angularVelocity) <= 0.01
        and previous.moving == current.moving
end

local function distance(dx, dy, dz)
    local largest = math.max(math.abs(dx), math.abs(dy), math.abs(dz or 0))
    if largest == 0 then
        return 0
    end

    local nx = dx / largest
    local ny = dy / largest
    local nz = (dz or 0) / largest
    return largest * math.sqrt(nx * nx + ny * ny + nz * nz)
end

local function relevanceRadius(config)
    local configured = type(config) == "table" and config.highwayRadius or nil
    return clamp(configured, 10, 500, 300)
end

local function assistMode(config)
    if type(config) == "table" and config.authoritativeAssist == true then
        return "enabled"
    end

    return "disabled"
end

function VehiclePolicy.interval(pingMs)
    local ping = clamp(pingMs, 0, 5000, 0)
    return clamp(100 + 0.5 * ping, 100, 250, 100)
end

function VehiclePolicy.isVisualPartAllowed(partId)
    return partId == "hood" or partId == "trunk" or partId == "rear-door"
end

function VehiclePolicy.acceptAuthority(state, vehicleId, driverId, epoch, nowMs)
    if type(state) ~= "table" then
        return false, "state"
    end

    if not nonEmptyString(vehicleId) or not nonEmptyString(driverId) then
        return false, "identity"
    end

    if not positiveInteger(epoch) or not isFinite(nowMs) then
        return false, "order"
    end

    state.authorities = state.authorities or {}
    local current = state.authorities[vehicleId]
    if current ~= nil and epoch <= current.epoch then
        return false, "stale"
    end

    state.authorities[vehicleId] = {
        driverId = driverId,
        epoch = epoch,
        changedAtMs = nowMs
    }
    state.confirmations = state.confirmations or {}
    state.confirmations[vehicleId] = nil

    return true, "ok"
end

function VehiclePolicy.reconcile(state, observer, report, nowMs, config)
    if type(state) ~= "table" or type(observer) ~= "table" or type(report) ~= "table" or not isFinite(nowMs) then
        return decision("none", "invalid", nil, assistMode(config))
    end

    local authority = state.authorities and state.authorities[report.vehicleId]
    if authority == nil or authority.driverId ~= report.driverId or authority.epoch ~= report.epoch then
        return decision("none", "authority", nil, assistMode(config))
    end

    if observer.isLocalDriver == true then
        return decision("none", "local-driver", nil, assistMode(config))
    end

    if not positiveInteger(report.sequence) then
        return decision("none", "sequence", nil, assistMode(config))
    end

    state.lastReportSequences = state.lastReportSequences or {}
    local previousSequence = state.lastReportSequences[report.vehicleId]
    if previousSequence ~= nil and report.sequence <= previousSequence then
        return decision("none", "sequence", nil, assistMode(config))
    end
    state.lastReportSequences[report.vehicleId] = report.sequence

    local target = VehiclePolicy.sanitizeTransform(report.transform)
    local observerX = clamp(observer.x, -300000, 300000, 0)
    local observerY = clamp(observer.y, -300000, 300000, 0)
    local radius = relevanceRadius(config)
    local rx = target.x - observerX
    local ry = target.y - observerY
    if distance(rx, ry, 0) > radius then
        return decision("none", "irrelevant", nil, assistMode(config))
    end

    local localTransform = VehiclePolicy.sanitizeTransform(observer.transform)
    local dx = target.x - localTransform.x
    local dy = target.y - localTransform.y
    local dz = target.z - localTransform.z
    local divergence = distance(dx, dy, dz)
    local trailer = report.isTrailer == true
    local softThreshold = trailer and 1.25 or 2.5
    if divergence <= softThreshold then
        if state.confirmations then
            state.confirmations[report.vehicleId] = nil
        end
        return decision("none", "within-threshold", nil, assistMode(config))
    end

    state.confirmations = state.confirmations or {}
    local confirmation = state.confirmations[report.vehicleId]
    if confirmation == nil or not matching(confirmation.target, target) or confirmation.trailer ~= trailer then
        confirmation = { count = 0, target = target, trailer = trailer }
        state.confirmations[report.vehicleId] = confirmation
    end
    confirmation.count = confirmation.count + 1

    local required = trailer and 2 or 3
    if confirmation.count < required then
        return decision("none", "unconfirmed", nil, assistMode(config))
    end

    state.confirmations[report.vehicleId] = nil
    local hardThreshold = trailer and 12.5 or 25
    if divergence > hardThreshold then
        return decision("vanilla-reset", "large-delta", target, assistMode(config))
    end

    return decision("interpolate", "confirmed", target, assistMode(config))
end

return VehiclePolicy
