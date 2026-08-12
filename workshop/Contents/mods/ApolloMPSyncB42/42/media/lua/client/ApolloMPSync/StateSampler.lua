local ActionRegistry = require("ApolloMPSync/ActionRegistry")
local VehiclePolicy = require("ApolloMPSync/VehiclePolicy")

local StateSampler = {
    disabledCategories = {}
}

local allowedPhases = {
    start = true,
    progress = true,
    complete = true,
    cancel = true
}

local function isFinite(value)
    return type(value) == "number"
        and value == value
        and value ~= math.huge
        and value ~= -math.huge
end

local function copyDescriptor(descriptor)
    return {
        modId = descriptor.modId,
        workshopId = descriptor.workshopId,
        disposition = descriptor.disposition,
        actionKey = descriptor.actionKey
    }
end

local function apiAvailable(api, methods)
    if type(api) ~= "table" then
        return false
    end

    for index = 1, #methods do
        if type(api[methods[index]]) ~= "function" then
            return false
        end
    end
    return true
end

local function enter(category, api, methods, operation)
    if StateSampler.disabledCategories[category] ~= nil then
        return nil, "adapter-disabled"
    end

    if not apiAvailable(api, methods) then
        return nil, "api-unavailable"
    end

    local ok, result, reason = pcall(operation)
    if not ok then
        StateSampler.disabledCategories[category] = "adapter-error"
        return nil, "adapter-error"
    end

    return result, reason
end

local function playerPose(api, player)
    local pose = api.getPlayerPose(player)
    if type(pose) ~= "table"
        or not isFinite(pose.x) or pose.x < -300000 or pose.x > 300000
        or not isFinite(pose.y) or pose.y < -300000 or pose.y > 300000
        or not isFinite(pose.z) or pose.z ~= math.floor(pose.z) or pose.z < -32 or pose.z > 32
        or not isFinite(pose.facing) then
        return nil
    end

    return { x = pose.x, y = pose.y, z = pose.z, facing = pose.facing }
end

local function nonEmptyString(value)
    return type(value) == "string" and value ~= ""
end

local function objectId(value)
    return nonEmptyString(value)
        or (isFinite(value) and value >= 1 and value <= 2147483647 and value == math.floor(value))
end

local function validSequence(value)
    return isFinite(value) and value >= 0 and value <= 2147483647 and value == math.floor(value)
end

local function safeAnimationValue(value)
    return type(value) == "boolean"
        or isFinite(value)
        or (type(value) == "string" and #value <= 64)
end

function StateSampler.adapterDescriptor(modId)
    local descriptor = ActionRegistry.compatibilityAdapter(modId)
    if descriptor == nil then
        return nil, "vanilla-fallback"
    end

    return copyDescriptor(descriptor), "ok"
end

function StateSampler.player(api, player)
    return enter("player", api, {
        "getPlayerId",
        "getPlayerPose",
        "getPlayerPingMs",
        "getCurrentAction",
        "getActionClassName"
    }, function()
        local id = api.getPlayerId(player)
        local pose = playerPose(api, player)
        local latency = api.getPlayerPingMs(player)
        if not nonEmptyString(id) or pose == nil or not isFinite(latency) then
            return nil, "state"
        end

        if latency < 0 then
            latency = 0
        elseif latency > 5000 then
            latency = 5000
        end

        local action = api.getCurrentAction(player)
        local actionKey
        if action ~= nil then
            actionKey = ActionRegistry.resolve(api.getActionClassName(action)).key
        end

        return {
            id = id,
            x = pose.x,
            y = pose.y,
            z = pose.z,
            facing = pose.facing,
            latencyMs = latency,
            action = actionKey
        }, "ok"
    end)
end

function StateSampler.action(api, player)
    return enter("action", api, {
        "getPlayerId",
        "getPlayerPose",
        "getCurrentAction",
        "getActionClassName",
        "getActionPhase",
        "getActionProgress",
        "getActionSequence",
        "getAnimationVariable",
        "nowMs"
    }, function()
        local playerId = api.getPlayerId(player)
        local action = api.getCurrentAction(player)
        if action == nil then
            return nil, "no-action"
        end

        local className = api.getActionClassName(action)
        local phase = api.getActionPhase(action)
        local anchor = playerPose(api, player)
        local timestampMs = api.nowMs()
        if not nonEmptyString(playerId) or not nonEmptyString(className)
            or not allowedPhases[phase] or anchor == nil
            or not isFinite(timestampMs) or timestampMs < 0 then
            return nil, "state"
        end

        local descriptor = ActionRegistry.resolve(className)
        local animationVars = {}
        for index = 1, #descriptor.animationVars do
            local name = descriptor.animationVars[index]
            local value = api.getAnimationVariable(player, name)
            if value ~= nil and safeAnimationValue(value) then
                animationVars[name] = value
            end
        end

        local snapshot = {
            playerId = playerId,
            key = descriptor.key,
            category = descriptor.category,
            phase = phase,
            anchor = anchor,
            animationVars = animationVars,
            timestampMs = timestampMs
        }

        local progress = api.getActionProgress(action)
        if progress ~= nil then
            if not isFinite(progress) or progress < 0 or progress > 1 then
                return nil, "progress"
            end
            snapshot.progress = progress
        end

        local sequence = api.getActionSequence(action)
        if sequence ~= nil then
            if not validSequence(sequence) then
                return nil, "sequence"
            end
            snapshot.sequence = sequence
        end

        return snapshot, "ok"
    end)
end

function StateSampler.vehicle(api, player)
    return enter("vehicle", api, {
        "getVehicleForPlayer",
        "getVehicleId",
        "getVehicleDriverId",
        "getVehicleTransform"
    }, function()
        local vehicle = api.getVehicleForPlayer(player)
        if vehicle == nil then
            return nil, "no-vehicle"
        end

        local vehicleId = api.getVehicleId(vehicle)
        local driverId = api.getVehicleDriverId(vehicle)
        if not objectId(vehicleId) or not nonEmptyString(driverId) then
            return nil, "identity"
        end

        return {
            vehicleId = vehicleId,
            driverId = driverId,
            transform = VehiclePolicy.sanitizeTransform(api.getVehicleTransform(vehicle))
        }, "ok"
    end)
end

function StateSampler.trailer(api, vehicle)
    return enter("trailer", api, {
        "getVehicleId",
        "getTrailerForVehicle",
        "getTowingVehicleId",
        "getVehicleTransform"
    }, function()
        local trailer = api.getTrailerForVehicle(vehicle)
        if trailer == nil then
            return nil, "no-trailer"
        end

        local trailerId = api.getVehicleId(trailer)
        local towingVehicleId = api.getTowingVehicleId(trailer)
        if not objectId(trailerId) or not objectId(towingVehicleId) then
            return nil, "identity"
        end

        return {
            trailerId = trailerId,
            towingVehicleId = towingVehicleId,
            transform = VehiclePolicy.sanitizeTransform(api.getVehicleTransform(trailer))
        }, "ok"
    end)
end

return StateSampler
