package.path = "./?.lua;./?/init.lua;workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/?.lua;workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/client/?.lua;workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/server/?.lua;" .. package.path

local A = require("tests.support.assertions")
local ClientRuntime = require("ApolloMPSync/ClientRuntime")
local Config = require("ApolloMPSync/Config")
local Protocol = require("ApolloMPSync/Protocol")
local ServerRuntime = require("ApolloMPSync/ServerRuntime")

local forbiddenCalls = {
    setX = 0,
    setY = 0,
    setZ = 0,
    setWorldTransform = 0,
    teleportVehicle = 0,
    applyPlayerPose = 0,
    applyVehicleTransform = 0
}

local function forbiddenObject(id)
    local object = { id = id, x = 0, y = 0, z = 0 }
    for _, name in ipairs({ "setX", "setY", "setZ", "setWorldTransform", "teleportVehicle" }) do
        object[name] = function()
            forbiddenCalls[name] = forbiddenCalls[name] + 1
        end
    end
    return object
end

local remote = forbiddenObject("remote")
local vehicle = forbiddenObject("vehicle")
local trailer = forbiddenObject("trailer")
local localPlayer = { id = "local", x = 0, y = 0, z = 0 }
local clock = 0
local api = {
    nowMs = function() return clock end,
    getPlayerByOnlineId = function() return remote end,
    getPlayerPose = function(player)
        return { x = player.x, y = player.y, z = player.z, facing = 0 }
    end,
    getLocalPlayer = function() return localPlayer end,
    getPlayerId = function(player) return player.id end,
    getVehicleById = function(id) return id == 42 and vehicle or trailer end,
    getVehicleTransform = function(object)
        return {
            x = object.x, y = object.y, z = object.z, angle = 0,
            vx = 0, vy = 0, angularVelocity = 0, moving = false
        }
    end,
    isLocalDriver = function() return false end,
    isLocalTrailerAuthority = function() return false end,
    applyPlayerPose = function(player, pose)
        forbiddenCalls.applyPlayerPose = forbiddenCalls.applyPlayerPose + 1
        player:setX(pose.x)
        player:setY(pose.y)
        player:setZ(pose.z)
    end,
    applyVehicleTransform = function(object, transform)
        forbiddenCalls.applyVehicleTransform = forbiddenCalls.applyVehicleTransform + 1
        object:setX(transform.x)
        object:setY(transform.y)
        object:setZ(transform.z)
        object:setWorldTransform(transform)
        object:teleportVehicle(transform.x, transform.y, transform.z)
    end
}

local config = Config.defaults()
config.confirmations = 1
local runtime = ClientRuntime.new(api, config)

-- Break caught: restoring any Lua player pose or vehicle/trailer transform correction path.
clock = 100
runtime:observePlayerHint({ actorOnlineId = 7, x = 8, y = 0, z = 0, facing = 0, latencyMs = 0 })
for sequence = 1, 3 do
    clock = clock + 100
    runtime:observeVehicleHint(Protocol.CHANNELS.vehicle, {
        vehicleId = 42, driverOnlineId = 7, epoch = 1, sequence = sequence,
        transform = { x = 8, y = 0, z = 0, angle = 0, vx = 0, vy = 0,
            angularVelocity = 0, moving = false }
    })
    runtime:observeVehicleHint(Protocol.CHANNELS.trailer, {
        trailerId = 43, driverOnlineId = 7, epoch = 1, sequence = sequence,
        transform = { x = 8, y = 0, z = 0, angle = 0, vx = 0, vy = 0,
            angularVelocity = 0, moving = false }
    })
end
A.equal(ClientRuntime.renderPlayer, nil)
A.equal(ClientRuntime.renderVehicle, nil)
for name, count in pairs(forbiddenCalls) do
    A.equal(count, 0)
end

-- Break caught: making advisory native hints non-idempotent or turning them into mutations.
api.getNativeAssistStatus = function()
    return { available = true, mode = "native", authority = "server" }
end
local hint = {
    actorOnlineId = 7, x = 8, y = 0, z = 0, facing = 0, latencyMs = 0,
    epoch = "native-hint", sequence = 1
}
runtime:onServerCommand(Protocol.NAMESPACE, Protocol.CHANNELS.player, hint)
runtime:onServerCommand(Protocol.NAMESPACE, Protocol.CHANNELS.player, hint)
A.equal(runtime.diagnostics.accepted.player, 1)
A.equal(runtime.diagnostics.rejected.stale, 1)
for _, count in pairs(forbiddenCalls) do A.equal(count, 0) end

local defaultApi = ClientRuntime.defaultApi()
A.equal(defaultApi.applyPlayerPose, nil)
A.equal(defaultApi.applyVehicleTransform, nil)

-- Break caught: removing safe semantic rendering while deleting coordinate correction.
local animationVars = {}
local reading = false
api.isLocalPlayer = function() return false end
api.applyAnimationVariable = function(_, name, value) animationVars[name] = value end
api.applyReadingState = function(_, active, _, readType)
    reading = active == true
    animationVars.ReadType = active and readType or nil
end

local semanticCases = {
    { name = "reading", key = "read", variable = "ReadType", value = "Book", cleared = nil },
    { name = "sitting", key = "sit", variable = "isSitOnGround", value = true, cleared = false },
    { name = "sleeping", key = "sleep", variable = "PerformingAction", value = true, cleared = false },
    { name = "resting", key = "rest", variable = "PerformingAction", value = true, cleared = false },
    { name = "generic timed action", key = "timed-action", variable = "PerformingAction",
        value = true, cleared = false },
    { name = "climb window", key = "climb-window", variable = "ClimbWindow",
        value = true, cleared = false },
    { name = "climb fence", key = "climb-fence", variable = "ClimbFence",
        value = true, cleared = false },
    { name = "climb ladder", key = "climb-ladder", variable = "ClimbLadder",
        value = true, cleared = false },
    { name = "vehicle entry", key = "vehicle-enter", variable = "VehicleTransition",
        value = true, cleared = false },
    { name = "vehicle exit", key = "vehicle-exit", variable = "VehicleTransition",
        value = true, cleared = false },
    -- Seat changes use the existing generic timed-action route; no new wire action is introduced.
    { name = "vehicle seat change", key = "timed-action", variable = "PerformingAction",
        value = true, cleared = false }
}
local function semanticEqual(actual, expected, case, stage)
    if actual ~= expected then
        error(case.name .. " " .. stage .. ": expected " .. tostring(expected)
            .. ", got " .. tostring(actual), 2)
    end
end
for index, case in ipairs(semanticCases) do
    clock = 1000 + index * 10
    local started, startReason = runtime:renderAction({
        actorOnlineId = 7,
        kind = "action",
        key = case.key,
        phase = "start",
        animationVars = { PerformingAction = true, IsReading = true,
            ReadType = "Book", isSitOnGround = true, ClimbWindow = true,
            ClimbFence = true, ClimbLadder = true, VehicleTransition = true }
    })
    semanticEqual(startReason, "ok", case, "start reason")
    semanticEqual(started, true, case, "start result")
    semanticEqual(animationVars[case.variable], case.value, case, "start variable")

    local cleared, clearReason = runtime:renderAction({
        actorOnlineId = 7, kind = "action", key = case.key, phase = "complete", animationVars = {}
    })
    semanticEqual(cleared, true, case, "clear result")
    semanticEqual(clearReason, "ok", case, "clear reason")
    semanticEqual(animationVars[case.variable], case.cleared, case, "clear variable")
end
A.equal(reading, false)
for _, count in pairs(forbiddenCalls) do A.equal(count, 0) end

-- Break caught: exposing a native mutation bridge or failing open when no status is published.
local nativeLoaded, NativeAssistClient = pcall(require, "ApolloMPSync/NativeAssistClient")
A.equal(nativeLoaded, true)
A.equal(type(NativeAssistClient.status), "function")
for name in pairs(NativeAssistClient) do A.equal(name, "status") end
local fallback = NativeAssistClient.status({})
local function assertCompleteFallback(value)
    A.equal(value.available, false)
    A.equal(value.mode, "fallback")
    A.equal(value.authority, "vanilla")
    A.equal(value.reason, "bridge-unavailable")
    A.equal(value.detail, nil)
    local fields = 0
    for _ in pairs(value) do fields = fields + 1 end
    A.equal(fields, 4)
end
assertCompleteFallback(fallback)

-- Break caught: trusting `available` independently of a coherent native/server record.
local malformedStatuses = {
    { available = true },
    { available = true, mode = "fallback", authority = "server" },
    { available = true, mode = "native", authority = "vanilla" },
    { available = false, mode = "native", authority = "server" },
    { available = "true", mode = "native", authority = "server" },
    { available = true, mode = string.rep("n", 65), authority = "server" },
    { available = true, mode = "native", authority = string.rep("s", 65) },
    { available = true, mode = "native", authority = "server", reason = 42 },
    { available = true, mode = "native", authority = "server", reason = "" },
    { available = true, mode = "native", authority = "server", detail = {} },
    { available = true, mode = "native", authority = "server", detail = string.rep("d", 257) }
}
for _, malformed in ipairs(malformedStatuses) do
    assertCompleteFallback(NativeAssistClient.status({
        getNativeAssistStatus = function() return malformed end
    }))
end

api.getNativeAssistStatus = function() return { available = true } end
local fallbackCount = runtime.diagnostics.fallbacks
local observed, observedReason = runtime:observePlayerHint({ actorOnlineId = 7 })
A.equal(observed, true)
A.equal(observedReason, "vanilla-fallback")
A.equal(runtime.diagnostics.fallbacks, fallbackCount + 1)

local published = { available = true, mode = "native", authority = "server", detail = "ready" }
local status = NativeAssistClient.status({ getNativeAssistStatus = function() return published end })
A.equal(status.available, true)
A.equal(status.mode, "native")
A.equal(status.authority, "server")
status.mode = "changed"
A.equal(published.mode, "native")

-- Break caught: accepting explicitly incompatible Apollo envelopes or consuming vanilla traffic.
A.equal(Protocol.isCompatible({}), true)
A.equal(Protocol.isCompatible({ protocolVersion = Protocol.VERSION }), true)
A.equal(Protocol.isCompatible({ protocolVersion = Protocol.VERSION + 1 }), false)
local rejectedBefore = runtime.diagnostics.rejected["protocol-version"] or 0
runtime:onServerCommand("VanillaModule", Protocol.CHANNELS.player, {
    protocolVersion = Protocol.VERSION + 1, sequence = 1
})
A.equal(runtime.diagnostics.rejected["protocol-version"] or 0, rejectedBefore)
runtime:onServerCommand(Protocol.NAMESPACE, Protocol.CHANNELS.player, {
    protocolVersion = Protocol.VERSION + 1, sequence = 1
})
A.equal(runtime.diagnostics.rejected["protocol-version"], rejectedBefore + 1)
local server = ServerRuntime.new({ nowMs = function() return 0 end }, Config.defaults())
local compatible, incompatibleReason = server:checkEnvelope("sender", 7,
    Protocol.CHANNELS.player, { protocolVersion = Protocol.VERSION + 1 }, 0)
A.equal(compatible, false)
A.equal(incompatibleReason, "protocol-version")
local serverRejectedBefore = server.diagnostics.rejected["protocol-version"] or 0
server:onClientCommand("VanillaModule", Protocol.CHANNELS.player, {}, {
    protocolVersion = Protocol.VERSION + 1
})
A.equal(server.diagnostics.rejected["protocol-version"] or 0, serverRejectedBefore)

-- Break caught: allowing hostile SandboxVars to opt Lua back into direct movement authority.
local safeDefaults = Config.defaults()
A.equal(safeDefaults.directPlayerCorrection, false)
A.equal(safeDefaults.directVehicleCorrection, false)
A.equal(safeDefaults.DirectPlayerCorrection, false)
A.equal(safeDefaults.DirectVehicleCorrection, false)
local hostile = Config.fromSandbox({
    DirectPlayerCorrection = true,
    DirectVehicleCorrection = true,
    AuthoritativeAssist = true
})
A.equal(hostile.directPlayerCorrection, false)
A.equal(hostile.directVehicleCorrection, false)

print("PASS native-only authority")
