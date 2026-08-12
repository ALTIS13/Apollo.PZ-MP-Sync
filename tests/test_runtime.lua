local A = require("tests.support.assertions")
local FakePZ = require("tests.support.fake_pz")
local Protocol = require("ApolloMPSync/Protocol")
local ClientRuntime = require("ApolloMPSync/ClientRuntime")
local ServerRuntime = require("ApolloMPSync/ServerRuntime")

local function transform(x, y)
    return {
        x = x,
        y = y,
        z = 0,
        angle = 0,
        vx = 0,
        vy = 0,
        angularVelocity = 0,
        moving = x ~= 0 or y ~= 0
    }
end

local function installEvents(api, names)
    api.events = {}
    for _, name in ipairs(names) do
        api.events[name] = {}
    end

    function api.addEvent(name, handler)
        local handlers = api.events[name]
        if handlers == nil then
            return false
        end
        handlers[#handlers + 1] = handler
        return true
    end

    function api.fire(name, ...)
        local handlers = api.events[name] or {}
        for index = 1, #handlers do
            handlers[index](...)
        end
    end
end

local function commandsFor(commands, channel)
    local found = {}
    for index = 1, #commands do
        if commands[index].command == channel then
            found[#found + 1] = commands[index]
        end
    end
    return found
end

local function lastFor(commands, channel)
    local found = commandsFor(commands, channel)
    return found[#found]
end

-- Break caught: treating a successful void-returning Build 42 part setter as failure and
-- skipping the verified transmitPartCondition call in the guarded default adapter.
local defaultServerApi = ServerRuntime.defaultApi()
local vanillaPart = { condition = 0 }
function vanillaPart:setCondition(condition) self.condition = condition end
local vanillaVehicle = { transmitted = false, requestedPartId = nil }
function vanillaVehicle:getPartById(partId)
    self.requestedPartId = partId
    return partId == "EngineDoor" and vanillaPart or nil
end
function vanillaVehicle:transmitPartCondition(part)
    self.transmitted = part == vanillaPart
end
A.equal(defaultServerApi.applyVehiclePart(vanillaVehicle, "hood", 88), true)
A.equal(vanillaVehicle.requestedPartId, "EngineDoor")
A.equal(vanillaPart.condition, 88)
A.equal(vanillaVehicle.transmitted, true)

local function configureClientApi(api)
    installEvents(api, {
        "OnPlayerUpdate",
        "OnServerCommand",
        "OnPlayerDeath",
        "OnDisconnect",
        "OnEnterVehicle",
        "OnExitVehicle",
        "OnSwitchVehicleSeat"
    })
    api.clientCommands = {}
    api.diagnostics = {}
    api.packetLogs = {}

    function api.sendClientCommand(moduleName, commandName, payload)
        api.clientCommands[#api.clientCommands + 1] = {
            module = moduleName,
            command = commandName,
            args = payload
        }
    end

    function api.getPlayerById(id)
        return api.players[id]
    end

    function api.getPlayerByOnlineId(onlineId)
        for _, player in pairs(api.players) do
            if player.onlineId == onlineId then return player end
        end
    end

    function api.getVehicleById(id)
        return api.vehicles[id]
    end

    function api.getLocalPlayer()
        for _, player in pairs(api.players) do
            if player.localPlayer then
                return player
            end
        end
    end

    function api.getObservedActionClasses(modId)
        if modId == "SleepWithFriends" then
            return { "ISSleepWithFriendsAction" }
        end
        return {}
    end

    function api.logDiagnostics(summary)
        api.diagnostics[#api.diagnostics + 1] = summary
    end

    function api.logPacket(value)
        api.packetLogs[#api.packetLogs + 1] = value
    end
end

-- Break caught: a client runtime bypassing the canonical command envelope, reusing a
-- sequence across channels, missing an action lifecycle edge, or inventing a trailer event.
local clientApi = FakePZ.new()
configureClientApi(clientApi)
local localPlayer = clientApi.addPlayer({
    id = "local",
    steamId = "steam-local",
    onlineId = 11,
    localPlayer = true,
    pingMs = 40,
    vehicleId = 101
})
local localVehicle = clientApi.addVehicle({
    id = 101,
    driverId = "local",
    trailerId = 102
})
clientApi.addVehicle({ id = 102, towingVehicleId = 101, x = -2 })
local remotePlayer = clientApi.addPlayer({
    id = "remote", steamId = "steam-remote", onlineId = 12, x = 0, y = 0
})
local remoteVehicle = clientApi.addVehicle({ id = 103, driverId = "remote" })

local client = ClientRuntime.install(clientApi, {
    enabled = true,
    playerSync = true,
    actionSync = true,
    actionEnabled = true,
    sleepEnabled = true,
    categoryEnabled = {},
    progressIntervalMs = 500,
    endGraceMs = 1250,
    movingIntervalMs = 200,
    idleIntervalMs = 1000,
    vehicleTransformSync = true,
    vehicleVisualPartSync = true,
    vehicleIntervalMinMs = 100,
    vehicleIntervalMaxMs = 250,
    trailerSync = true,
    diagnosticsIntervalSeconds = 60,
    confirmations = 4,
    softThreshold = 2.5,
    maxStep = 0.25,
    highwayRadius = 300
})
A.equal(#clientApi.events.OnPlayerUpdate, 1)
A.equal(#clientApi.events.OnServerCommand, 1)
A.equal(#clientApi.events.OnPlayerDeath, 1)
A.equal(#clientApi.events.OnDisconnect, 1)
A.equal(#clientApi.events.OnEnterVehicle, 1)
A.equal(#clientApi.events.OnExitVehicle, 1)
A.equal(#clientApi.events.OnSwitchVehicleSeat, 1)

clientApi.fire("OnPlayerUpdate", localPlayer)
local firstPlayer = lastFor(clientApi.clientCommands, Protocol.CHANNELS.player)
local firstVehicle = lastFor(clientApi.clientCommands, Protocol.CHANNELS.vehicle)
local firstTrailer = lastFor(clientApi.clientCommands, Protocol.CHANNELS.trailer)
A.equal(firstPlayer.module, Protocol.NAMESPACE)
A.equal(firstPlayer.args.sequence, 1)
A.equal(type(firstPlayer.args.epoch), "string")
A.equal(firstVehicle.args.sequence, 1)
A.equal(firstTrailer.args.operation, "attach")
A.equal(firstTrailer.args.sequence, 1)

clientApi.clock.nowMs = 1200
localPlayer.x = 1
localPlayer.currentAction = {
    className = "ISReadABook",
    phase = "start",
    progress = 0,
    sequence = 99,
    animationVars = { PerformingAction = true, IsReading = true }
}
clientApi.fire("OnPlayerUpdate", localPlayer)
A.equal(lastFor(clientApi.clientCommands, Protocol.CHANNELS.player).args.sequence, 2)
local actionStart = lastFor(clientApi.clientCommands, Protocol.CHANNELS.action)
A.equal(actionStart.args.key, "read")
A.equal(actionStart.args.phase, "start")
A.equal(actionStart.args.sequence, 1)

clientApi.clock.nowMs = 1700
localPlayer.currentAction.phase = "progress"
localPlayer.currentAction.progress = 0.5
clientApi.fire("OnPlayerUpdate", localPlayer)
A.equal(lastFor(clientApi.clientCommands, Protocol.CHANNELS.action).args.sequence, 2)
clientApi.clock.nowMs = 2200
localPlayer.currentAction.phase = "complete"
localPlayer.currentAction.progress = 1
clientApi.fire("OnPlayerUpdate", localPlayer)
A.equal(lastFor(clientApi.clientCommands, Protocol.CHANNELS.action).args.sequence, 3)
A.equal(client.actionGraceUntilMs, 3450)

clientApi.clock.nowMs = 2450
localVehicle.trailerId = nil
localPlayer.currentAction = nil
clientApi.fire("OnPlayerUpdate", localPlayer)
local trailerDetach = lastFor(clientApi.clientCommands, Protocol.CHANNELS.trailer)
A.equal(trailerDetach.args.operation, "detach")
A.equal(trailerDetach.args.trailerId, 102)
A.equal(trailerDetach.args.sequence, 2)

local oldEpoch = firstPlayer.args.epoch
local beforeDeathSequence = lastFor(clientApi.clientCommands, Protocol.CHANNELS.player).args.sequence
clientApi.fire("OnPlayerDeath", localPlayer)
clientApi.clock.nowMs = 4000
clientApi.fire("OnPlayerUpdate", localPlayer)
local resetPlayer = lastFor(clientApi.clientCommands, Protocol.CHANNELS.player)
A.equal(resetPlayer.args.sequence, beforeDeathSequence + 1)
A.equal(resetPlayer.args.epoch, oldEpoch)

-- Break caught: restoring Lua correction packets for any player, vehicle, or trailer.
for sequence = 1, 4 do
    clientApi.clock.nowMs = 5000 + sequence * 100
    clientApi.fire("OnServerCommand", Protocol.NAMESPACE, Protocol.CHANNELS.player, {
        kind = "player",
        actorOnlineId = remotePlayer.onlineId,
        x = 6,
        y = 0,
        z = 0,
        facing = 0,
        latencyMs = 0,
        epoch = "server-player",
        sequence = sequence
    })
end
A.equal(remotePlayer.x, 0)
A.equal(localPlayer.x, 1)
clientApi.fire("OnServerCommand", Protocol.NAMESPACE, Protocol.CHANNELS.action, {
    kind = "action",
    actorOnlineId = localPlayer.onlineId,
    key = "read",
    animationVars = { PerformingAction = false },
    epoch = "server-action",
    sequence = 1
})
A.equal(localPlayer.animationVars.PerformingAction, nil)

for sequence = 1, 3 do
    clientApi.clock.nowMs = 6000 + sequence * 100
    clientApi.fire("OnServerCommand", Protocol.NAMESPACE, Protocol.CHANNELS.vehicle, {
        kind = "vehicle",
        vehicleId = 103,
        driverOnlineId = remotePlayer.onlineId,
        epoch = 1,
        sequence = sequence,
        transform = transform(6, 0)
    })
end
A.equal(remoteVehicle.x, 0)
clientApi.fire("OnServerCommand", Protocol.NAMESPACE, Protocol.CHANNELS.vehicle, {
    kind = "vehicle",
    vehicleId = 101,
    driverOnlineId = localPlayer.onlineId,
    epoch = 1,
    sequence = 1,
    transform = transform(99, 0)
})
A.equal(localVehicle.x, 0)
A.equal(#clientApi.packetLogs, 0)

local function configureServerApi(api)
    installEvents(api, { "OnClientCommand", "OnTick" })
    api.serverCommands = {}
    api.diagnostics = {}
    api.packetLogs = {}
    api.repairCalls = {}

    function api.getStablePlayerId(player)
        return player and player.steamId or nil
    end

    function api.getPlayerOnlineId(player)
        return player and player.onlineId or nil
    end

    function api.getObservedPlayer(player)
        if player == nil then
            return nil
        end
        return { id = player.id, x = player.x, y = player.y, z = player.z }
    end

    function api.getOnlinePlayers()
        local players = {}
        for _, player in pairs(api.players) do
            players[#players + 1] = player
        end
        return players
    end

    function api.getVehicleById(id)
        if id == 999 then
            error("fake vehicle lookup failure")
        end
        return api.vehicles[id]
    end

    function api.getVehicleDriver(vehicle)
        return vehicle and vehicle.driverId and api.players[vehicle.driverId] or nil
    end

    function api.getVehicleId(vehicle)
        return vehicle and vehicle.id or nil
    end

    function api.getVehicleTowedBy(trailer)
        return trailer and trailer.towingVehicleId and api.vehicles[trailer.towingVehicleId] or nil
    end

    function api.getObservedVehicle(vehicle)
        return vehicle and api.getVehicleTransform(vehicle) or nil
    end

    function api.sendServerCommand(player, moduleName, commandName, payload)
        api.serverCommands[#api.serverCommands + 1] = {
            recipientId = player.id,
            module = moduleName,
            command = commandName,
            args = payload
        }
    end

    function api.applyVehiclePart(vehicle, partId, condition)
        api.repairCalls[#api.repairCalls + 1] = {
            vehicleId = vehicle.id,
            partId = partId,
            condition = condition
        }
        vehicle.parts = vehicle.parts or {}
        vehicle.parts[partId] = condition
        return true
    end

    function api.logDiagnostics(summary)
        api.diagnostics[#api.diagnostics + 1] = summary
    end

    function api.logPacket(value)
        api.packetLogs[#api.packetLogs + 1] = value
    end
end

local function fireClient(api, channel, sender, payload)
    api.fire("OnClientCommand", Protocol.NAMESPACE, channel, sender, payload)
end

-- Break caught: trusting payload identity, relaying outside the relevant radius, sharing
-- rate/sequence state across channels, or logging once per accepted/rejected packet.
local serverApi = FakePZ.new()
configureServerApi(serverApi)
local driverA = serverApi.addPlayer({
    id = "driver-a", steamId = "steam-driver-a", onlineId = 21, x = 0, y = 0
})
local driverB = serverApi.addPlayer({
    id = "driver-b", steamId = "steam-driver-b", onlineId = 22, x = 1, y = 0
})
serverApi.addPlayer({ id = "near", steamId = "steam-near", onlineId = 23, x = 10, y = 0 })
serverApi.addPlayer({ id = "player-far", steamId = "steam-player-far", onlineId = 24, x = 100, y = 0 })
serverApi.addPlayer({ id = "vehicle-far", steamId = "steam-vehicle-far", onlineId = 25, x = 400, y = 0 })
local car = serverApi.addVehicle({ id = 301, driverId = "driver-a" })
serverApi.addVehicle({ id = 302, driverId = "driver-b", trailerId = 303 })
serverApi.addVehicle({ id = 303, towingVehicleId = 302 })
for index = 1, 5 do
    serverApi.addVehicle({ id = 400 + index, driverId = "driver-b" })
end

local server = ServerRuntime.install(serverApi, {
    enabled = true,
    actionEnabled = true,
    sleepEnabled = true,
    categoryEnabled = {},
    progressIntervalMs = 500,
    endGraceMs = 1250,
    playerRadius = 60,
    highwayRadius = 300,
    vehicleRadius = 300,
    vehicleRepairIntervalSeconds = 5,
    diagnosticsIntervalSeconds = 60,
    vehicleVisualPartSync = true
})
A.equal(#serverApi.events.OnClientCommand, 1)
A.equal(#serverApi.events.OnTick, 1)

fireClient(serverApi, Protocol.CHANNELS.player, driverA, {
    id = "victim",
    x = 1,
    y = 0,
    z = 0,
    facing = 0,
    latencyMs = 50,
    epoch = "driver-a-life",
    sequence = 1
})
local playerRelays = commandsFor(serverApi.serverCommands, Protocol.CHANNELS.player)
A.equal(#playerRelays, 2)
A.equal(playerRelays[1].args.actorOnlineId, driverA.onlineId)
A.equal(playerRelays[1].args.id, nil)
A.equal(playerRelays[1].module, Protocol.NAMESPACE)
A.equal(playerRelays[1].recipientId == "near" or playerRelays[2].recipientId == "near", true)
A.equal(playerRelays[1].recipientId == "player-far" or playerRelays[2].recipientId == "player-far", false)

serverApi.clock.nowMs = 1050
fireClient(serverApi, Protocol.CHANNELS.player, driverA, {
    x = 1, y = 0, z = 0, facing = 0, epoch = "driver-a-life", sequence = 2
})
A.equal(#commandsFor(serverApi.serverCommands, Protocol.CHANNELS.player), 2)
serverApi.clock.nowMs = 1100
fireClient(serverApi, Protocol.CHANNELS.player, driverA, {
    x = 1, y = 0, z = 0, facing = 0, epoch = "driver-a-life", sequence = 1
})
A.equal(#commandsFor(serverApi.serverCommands, Protocol.CHANNELS.player), 2)

-- The action channel has independent rate and sequence state. Its canonical key is the
-- StateSampler/ActionRegistry contract; the payload class is never trusted.
fireClient(serverApi, Protocol.CHANNELS.action, driverA, {
    key = "read",
    className = "UntrustedAction",
    phase = "start",
    progress = 0,
    anchor = { x = 0, y = 0, z = 0, facing = 0 },
    animationVars = { PerformingAction = true, IsReading = true },
    epoch = "driver-a-life",
    sequence = 1
})
A.equal(lastFor(serverApi.serverCommands, Protocol.CHANNELS.action).args.key, "read")
serverApi.clock.nowMs = 1300
fireClient(serverApi, Protocol.CHANNELS.action, driverA, {
    key = "read", phase = "progress", progress = 0.25,
    anchor = { x = 0, y = 0, z = 0, facing = 0 },
    animationVars = { PerformingAction = true }, epoch = "driver-a-life", sequence = 2
})
A.equal(#commandsFor(serverApi.serverCommands, Protocol.CHANNELS.action), 2)
serverApi.clock.nowMs = 1600
fireClient(serverApi, Protocol.CHANNELS.action, driverA, {
    key = "read", phase = "progress", progress = 0.5,
    anchor = { x = 0, y = 0, z = 0, facing = 0 },
    animationVars = { PerformingAction = true }, epoch = "driver-a-life", sequence = 3
})
A.equal(#commandsFor(serverApi.serverCommands, Protocol.CHANNELS.action), 4)
serverApi.clock.nowMs = 2100
fireClient(serverApi, Protocol.CHANNELS.action, driverA, {
    key = "read", phase = "complete", progress = 1,
    anchor = { x = 0, y = 0, z = 0, facing = 0 },
    animationVars = { PerformingAction = false }, epoch = "driver-a-life", sequence = 4
})
A.equal(lastFor(serverApi.serverCommands, Protocol.CHANNELS.action).args.graceUntilMs, 3350)

-- Break caught: trusting driverId/epoch from the payload or resetting a report sequence
-- during driver handoff. Server-observed current driver owns authority and relay order.
serverApi.clock.nowMs = 2200
fireClient(serverApi, Protocol.CHANNELS.vehicle, driverA, {
    vehicleId = 301,
    driverId = "imposter",
    authorityEpoch = 999,
    epoch = "driver-a-life",
    sequence = 1,
    transform = transform(1, 0)
})
local vehicleA = lastFor(serverApi.serverCommands, Protocol.CHANNELS.vehicle)
A.equal(vehicleA.args.driverOnlineId, driverA.onlineId)
A.equal(vehicleA.args.driverId, nil)
A.equal(vehicleA.args.epoch, 1)
A.equal(vehicleA.args.sequence, 1)
A.equal(#commandsFor(serverApi.serverCommands, Protocol.CHANNELS.vehicle), 3)

car.driverId = "driver-b"
serverApi.clock.nowMs = 2300
fireClient(serverApi, Protocol.CHANNELS.vehicle, driverB, {
    vehicleId = 301, driverId = "driver-a", epoch = "driver-b-life", sequence = 1,
    transform = transform(2, 0)
})
local vehicleB = lastFor(serverApi.serverCommands, Protocol.CHANNELS.vehicle)
A.equal(vehicleB.args.driverOnlineId, driverB.onlineId)
A.equal(vehicleB.args.epoch, 2)
A.equal(vehicleB.args.sequence, 2)
serverApi.clock.nowMs = 2400
local beforeOldDriver = #commandsFor(serverApi.serverCommands, Protocol.CHANNELS.vehicle)
fireClient(serverApi, Protocol.CHANNELS.vehicle, driverA, {
    vehicleId = 301, sequence = 2, epoch = "driver-a-life", transform = transform(3, 0)
})
A.equal(#commandsFor(serverApi.serverCommands, Protocol.CHANNELS.vehicle), beforeOldDriver)

-- Break caught: deriving trailer authority from the detach payload, accepting detach
-- before vanilla clears the tracked relation, or allowing a stale independent state.
serverApi.clock.nowMs = 2500
fireClient(serverApi, Protocol.CHANNELS.trailer, driverB, {
    operation = "attach", towingVehicleId = 302, trailerId = 303,
    transform = transform(4, 0), epoch = "driver-b-life", sequence = 1
})
serverApi.vehicles[302].trailerId = nil
serverApi.vehicles[303].towingVehicleId = nil
serverApi.clock.nowMs = 2600
fireClient(serverApi, Protocol.CHANNELS.trailer, driverB, {
    operation = "detach",
    towingVehicleId = 302,
    trailerId = 303,
    authorityDriverId = "imposter",
    transform = transform(4, 0),
    epoch = "driver-b-life",
    sequence = 2
})
A.equal(server.state.trailerPolicyById.trailers.trailers["303"].attachedTo, nil)
A.equal(server.state.trailerPolicyById.trailers.trailers["303"].authorityOwnerId, "steam-driver-b")
A.equal(lastFor(serverApi.serverCommands, Protocol.CHANNELS.trailer).args.operation, "detach")
serverApi.clock.nowMs = 2700
local trailerRelayCount = #commandsFor(serverApi.serverCommands, Protocol.CHANNELS.trailer)
fireClient(serverApi, Protocol.CHANNELS.trailer, driverB, {
    operation = "attach", towingVehicleId = 302, trailerId = 303,
    epoch = "driver-b-life", sequence = 2
})
A.equal(#commandsFor(serverApi.serverCommands, Protocol.CHANNELS.trailer), trailerRelayCount)

-- Break caught: resetting the relayed trailer report order when towing authority hands
-- from one current driver to another.
serverApi.vehicles[302].driverId = "driver-a"
serverApi.vehicles[302].trailerId = 303
serverApi.vehicles[303].towingVehicleId = 302
serverApi.clock.nowMs = 2800
fireClient(serverApi, Protocol.CHANNELS.trailer, driverA, {
    operation = "attach", towingVehicleId = 302, trailerId = 303,
    transform = transform(4, 0), epoch = "driver-a-life", sequence = 1
})
local handedOffTrailer = lastFor(serverApi.serverCommands, Protocol.CHANNELS.trailer)
A.equal(handedOffTrailer.args.driverOnlineId, driverA.onlineId)
A.equal(handedOffTrailer.args.epoch, 2)
A.equal(handedOffTrailer.args.sequence, 3)

-- Break caught: repairing a non-visual part or draining more than four queued vehicles
-- at the five-second repair tick.
for index = 1, 5 do
    serverApi.clock.nowMs = 2700 + index * 100
    fireClient(serverApi, Protocol.CHANNELS.parts, driverB, {
        vehicleId = 400 + index,
        parts = { { partId = "hood", condition = 80 + index, open = false } },
        epoch = "driver-b-life",
        sequence = index
    })
end
serverApi.clock.nowMs = 3300
local partsBefore = #commandsFor(serverApi.serverCommands, Protocol.CHANNELS.parts)
fireClient(serverApi, Protocol.CHANNELS.parts, driverB, {
    vehicleId = 401,
    parts = { { partId = "engine", condition = 100, open = false } },
    epoch = "driver-b-life", sequence = 6
})
A.equal(#commandsFor(serverApi.serverCommands, Protocol.CHANNELS.parts), partsBefore)
serverApi.clock.nowMs = 6000
serverApi.fire("OnTick")
A.equal(#serverApi.repairCalls, 4)
A.equal(server.repair:pendingVehicleCount(), 1)

-- Break caught: accepting a command without a callback sender or allowing one failing
-- adapter category to stop valid player traffic.
serverApi.clock.nowMs = 6200
fireClient(serverApi, Protocol.CHANNELS.player, nil, {
    x = 0, y = 0, z = 0, epoch = "nil", sequence = 1
})
fireClient(serverApi, Protocol.CHANNELS.vehicle, driverA, {
    vehicleId = 999, epoch = "driver-a-life", sequence = 3, transform = transform(0, 0)
})
A.equal(server.state.disabledCategories.vehicle, "adapter-error")
serverApi.clock.nowMs = 6400
local playersBeforeRecovery = #commandsFor(serverApi.serverCommands, Protocol.CHANNELS.player)
fireClient(serverApi, Protocol.CHANNELS.player, driverA, {
    x = 2, y = 0, z = 0, facing = 0, epoch = "driver-a-life", sequence = 2
})
A.equal(#commandsFor(serverApi.serverCommands, Protocol.CHANNELS.player), playersBeforeRecovery + 2)

-- Break caught: packet-level log spam or flushing diagnostics before/after the exact
-- aggregated 60-second window without accepted/rejected/fallback/adapter information.
A.equal(#serverApi.packetLogs, 0)
A.equal(#serverApi.diagnostics, 0)
serverApi.clock.nowMs = 61000
serverApi.fire("OnTick")
A.equal(#serverApi.diagnostics, 1)
A.equal(serverApi.diagnostics[1].accepted.player > 0, true)
A.equal(serverApi.diagnostics[1].accepted.vehicle > 0, true)
A.equal(serverApi.diagnostics[1].rejected.sender > 0, true)
A.equal(serverApi.diagnostics[1].disabledAdapters.vehicle, "adapter-error")
A.equal(type(serverApi.diagnostics[1].corrections), "number")
A.equal(type(serverApi.diagnostics[1].fallbacks), "number")
A.equal(#serverApi.packetLogs, 0)
serverApi.fire("OnTick")
A.equal(#serverApi.diagnostics, 1)

print("PASS runtime")
