local A = require("tests.support.assertions")
local ActionRegistry = require("ApolloMPSync/ActionRegistry")
local ClientRuntime = require("ApolloMPSync/ClientRuntime")
local Config = require("ApolloMPSync/Config")
local FakePZ = require("tests.support.fake_pz")
local Protocol = require("ApolloMPSync/Protocol")
local ServerRuntime = require("ApolloMPSync/ServerRuntime")

local failures = {}

local function regression(name, body)
    local ok, reason = pcall(body)
    if not ok then
        failures[#failures + 1] = name .. ": " .. tostring(reason)
        print("RED runtime round1 " .. name .. ": " .. tostring(reason))
    end
end

local function transform(x, y)
    return {
        x = x, y = y, z = 0, angle = 0,
        vx = 0, vy = 0, angularVelocity = 0, moving = x ~= 0 or y ~= 0
    }
end

local function eventSurface(api, names)
    api.events = {}
    for _, name in ipairs(names) do api.events[name] = {} end
    function api.addEvent(name, handler)
        local handlers = api.events[name]
        if handlers == nil then return false end
        handlers[#handlers + 1] = handler
        return true
    end
    function api.fire(name, ...)
        for _, handler in ipairs(api.events[name] or {}) do handler(...) end
    end
end

local function playerByOnlineId(api, onlineId)
    for _, player in pairs(api.players) do
        if player.onlineId == onlineId then return player end
    end
end

local function serverApi()
    local api = FakePZ.new()
    eventSurface(api, { "OnClientCommand", "OnTick" })
    api.serverCommands = {}
    api.diagnostics = {}
    api.throwVehicleId = nil

    function api.getStablePlayerId(player) return player and player.steamId or nil end
    function api.getPlayerOnlineId(player) return player and player.onlineId or nil end
    function api.getObservedPlayer(player)
        return player and { id = player.id, x = player.x, y = player.y, z = player.z } or nil
    end
    function api.getOnlinePlayers()
        local players = {}
        for _, player in pairs(api.players) do
            if player.connected ~= false then players[#players + 1] = player end
        end
        return players
    end
    function api.getVehicleById(id)
        if id == api.throwVehicleId then error("validated fake lookup failure") end
        return api.vehicles[id]
    end
    function api.getVehicleId(vehicle) return vehicle and vehicle.id or nil end
    function api.getVehicleDriver(vehicle)
        return vehicle and vehicle.driverId and api.players[vehicle.driverId] or nil
    end
    function api.getVehicleTowedBy(trailer)
        return trailer and trailer.towingVehicleId and api.vehicles[trailer.towingVehicleId] or nil
    end
    function api.getObservedVehicle(vehicle) return vehicle and api.getVehicleTransform(vehicle) or nil end
    function api.sendServerCommand(player, moduleName, channel, payload)
        api.serverCommands[#api.serverCommands + 1] = {
            recipientOnlineId = player.onlineId,
            module = moduleName,
            command = channel,
            args = payload
        }
    end
    function api.applyVehiclePart(vehicle, partId, condition)
        vehicle.parts = vehicle.parts or {}
        vehicle.parts[partId] = condition
        return true
    end
    function api.logDiagnostics(summary) api.diagnostics[#api.diagnostics + 1] = summary end
    return api
end

local function clientApi()
    local api = FakePZ.new()
    eventSurface(api, {
        "OnPlayerUpdate", "OnServerCommand", "OnPlayerDeath", "OnDisconnect",
        "OnEnterVehicle", "OnExitVehicle", "OnSwitchVehicleSeat"
    })
    api.clientCommands = {}
    api.diagnostics = {}
    function api.sendClientCommand(moduleName, channel, payload)
        api.clientCommands[#api.clientCommands + 1] = {
            module = moduleName, command = channel, args = payload
        }
        if api.forwardClientCommand then api.forwardClientCommand(moduleName, channel, payload) end
    end
    function api.getPlayerOnlineId(player) return player and player.onlineId or nil end
    function api.getPlayerByOnlineId(onlineId) return playerByOnlineId(api, onlineId) end
    function api.getVehicleById(id) return api.vehicles[id] end
    function api.getLocalPlayer()
        for _, player in pairs(api.players) do if player.localPlayer then return player end end
    end
    function api.getObservedActionClasses() return {} end
    function api.logDiagnostics(summary) api.diagnostics[#api.diagnostics + 1] = summary end
    return api
end

local function fireClient(api, channel, sender, payload)
    api.fire("OnClientCommand", Protocol.NAMESPACE, channel, sender, payload)
end

local function relays(api, channel)
    local found = {}
    for _, command in ipairs(api.serverCommands) do
        if command.command == channel then found[#found + 1] = command end
    end
    return found
end

local function clientCommands(api, channel)
    local found = {}
    for _, command in ipairs(api.clientCommands) do
        if command.command == channel then found[#found + 1] = command end
    end
    return found
end

local function config(overrides)
    local result = Config.defaults()
    for key, value in pairs(overrides or {}) do result[key] = value end
    return result
end

regression("identity-domains-and-observed-relevance", function()
    local defaultApi = ServerRuntime.defaultApi()
    local vanillaPlayer = {}
    function vanillaPlayer:getSteamID() return nil end
    function vanillaPlayer:getUsername() return "vanilla-user" end
    function vanillaPlayer:getOnlineID() return 0 end
    A.equal(defaultApi.getStablePlayerId(vanillaPlayer), "vanilla-user")
    A.equal(defaultApi.getPlayerOnlineId(vanillaPlayer), 0)

    local api = serverApi()
    local sender = api.addPlayer({
        id = "actor-key", username = "alice", steamId = "steam-A", onlineId = 0, x = 0
    })
    api.addPlayer({ id = "near-observed", username = "bob", steamId = "steam-B", onlineId = 12, x = -55 })
    api.addPlayer({ id = "far-observed", username = "cara", steamId = "steam-C", onlineId = 13, x = 70 })
    local runtime = ServerRuntime.install(api, config())
    fireClient(api, Protocol.CHANNELS.player, sender, {
        id = "steam-victim", x = 20, y = 0, z = 0, facing = 999, latencyMs = 6000,
        action = "read", epoch = "life-A", sequence = 1
    })
    local output = relays(api, Protocol.CHANNELS.player)
    A.equal(#output, 1)
    A.equal(output[1].recipientOnlineId, 12)
    A.equal(output[1].args.actorOnlineId, 0)
    A.equal(output[1].args.id, nil)
    A.equal(output[1].args.playerId, nil)
    A.equal(output[1].args.driverId, nil)
    A.equal(output[1].args.facing, 360)
    A.equal(output[1].args.latencyMs, 5000)
    A.equal(runtime.state.rateLimitByPlayerId["steam-A"] ~= nil, true)

    api.clock.nowMs = 1100
    local count = #output
    fireClient(api, Protocol.CHANNELS.player, sender, {
        x = 0, y = 0, z = 0, facing = 0 / 0, latencyMs = 0,
        epoch = "life-A", sequence = 2
    })
    A.equal(#relays(api, Protocol.CHANNELS.player), count)
    api.clock.nowMs = 1200
    fireClient(api, Protocol.CHANNELS.player, sender, {
        x = 0, y = 0, z = 0, facing = 0, latencyMs = math.huge, action = "invented",
        epoch = "life-A", sequence = 3
    })
    A.equal(#relays(api, Protocol.CHANNELS.player), count)
end)

regression("trailer-server-relation-origin-and-epoch", function()
    local api = serverApi()
    local driver = api.addPlayer({ id = "driver", steamId = "steam-D", onlineId = 21, x = 0 })
    api.addPlayer({ id = "trailer-near", steamId = "steam-N", onlineId = 22, x = 205 })
    api.addPlayer({ id = "payload-near-only", steamId = "steam-P", onlineId = 23, x = 5 })
    api.addVehicle({ id = 101, driverId = "driver", trailerId = 102, x = 0 })
    api.addVehicle({ id = 102, towingVehicleId = 101, x = 200 })
    api.addVehicle({ id = 103, driverId = "driver", x = 0 })
    local runtime = ServerRuntime.install(api, config({ vehicleRadius = 20, highwayRadius = 20 }))

    fireClient(api, Protocol.CHANNELS.trailer, driver, {
        operation = "detach", towingVehicleId = "101", trailerId = 102,
        transform = transform(200, 0), epoch = "life-D", sequence = 1
    })
    A.equal(runtime.state.disabledCategories.trailer, nil)
    A.equal(#relays(api, Protocol.CHANNELS.trailer), 0)

    api.clock.nowMs = 1100
    fireClient(api, Protocol.CHANNELS.trailer, driver, {
        operation = "detach", towingVehicleId = 103, trailerId = 102,
        transform = transform(200, 0), epoch = "life-D", sequence = 2
    })
    A.equal(#relays(api, Protocol.CHANNELS.trailer), 0)

    api.clock.nowMs = 1200
    fireClient(api, Protocol.CHANNELS.trailer, driver, {
        operation = "attach", towingVehicleId = 101, trailerId = 102,
        authorityDriverId = "forged", transform = transform(200, 0),
        epoch = "life-D", sequence = 3
    })
    local first = relays(api, Protocol.CHANNELS.trailer)
    A.equal(#first, 1)
    A.equal(first[1].recipientOnlineId, 22)
    A.equal(first[1].args.epoch, 1)
    A.equal(first[1].args.sequence, 1)
    A.equal(first[1].args.authorityDriverId, nil)
    A.equal(first[1].args.driverOnlineId, 21)

    api.vehicles[102].towingVehicleId = nil
    api.vehicles[101].trailerId = nil
    api.clock.nowMs = 1300
    fireClient(api, Protocol.CHANNELS.trailer, driver, {
        operation = "detach", towingVehicleId = 101, trailerId = 102,
        transform = transform(200, 0), epoch = "life-D", sequence = 4
    })
    A.equal(relays(api, Protocol.CHANNELS.trailer)[2].args.operation, "detach")

    api.vehicles[102].towingVehicleId = 103
    api.vehicles[103].trailerId = 102
    api.clock.nowMs = 1400
    fireClient(api, Protocol.CHANNELS.trailer, driver, {
        operation = "attach", towingVehicleId = 103, trailerId = 102,
        transform = transform(200, 0), epoch = "life-D", sequence = 5
    })
    local handedOff = relays(api, Protocol.CHANNELS.trailer)
    A.equal(handedOff[#handedOff].args.epoch, 2)
    A.equal(handedOff[#handedOff].args.sequence, 3)
end)

regression("inverse-tow-authority-client", function()
    local api = clientApi()
    local localPlayer = api.addPlayer({ id = "local", onlineId = 31, localPlayer = true, vehicleId = 301 })
    api.addPlayer({ id = "remote", onlineId = 32 })
    api.addVehicle({ id = 301, driverId = "local", trailerId = 302 })
    local trailer = api.addVehicle({ id = 302, towingVehicleId = 301 })
    A.equal(api.getVehicleTowedBy(trailer).id, 301)
    A.equal(api.getTrailerForVehicle(api.vehicles[301]).id, 302)
    A.equal(api.isLocalTrailerAuthority(trailer), true)
    local runtime = ClientRuntime.install(api, config())
    api.fire("OnPlayerUpdate", localPlayer)
    A.equal(clientCommands(api, Protocol.CHANNELS.trailer)[1].args.towingVehicleId, 301)
end)

regression("receive-config-gates-before-mutation", function()
    local api = serverApi()
    local sender = api.addPlayer({ id = "sender", steamId = "steam-gate", onlineId = 41 })
    api.addPlayer({ id = "near", steamId = "steam-near", onlineId = 42, x = 1 })
    local runtime = ServerRuntime.install(api, config({ actionSync = false, actionEnabled = false }))
    fireClient(api, Protocol.CHANNELS.action, sender, {
        key = "read", phase = "start", progress = 0,
        anchor = { x = 0, y = 0, z = 0, facing = 0 }, animationVars = {},
        epoch = "gate-life", sequence = 1
    })
    A.equal(runtime.state.actionPolicyByPlayerId["steam-gate"], nil)
    A.equal(runtime.state.sequenceByPlayerId["steam-gate"], nil)
    A.equal(#relays(api, Protocol.CHANNELS.action), 0)

    local capi = clientApi()
    capi.addPlayer({ id = "local", onlineId = 43, localPlayer = true })
    local remote = capi.addPlayer({ id = "remote", onlineId = 44 })
    ClientRuntime.install(capi, config({ playerSync = false }))
    capi.fire("OnServerCommand", Protocol.NAMESPACE, Protocol.CHANNELS.player, {
        actorOnlineId = 44, x = 9, y = 0, z = 0, facing = 0, latencyMs = 0,
        epoch = "remote-life", sequence = 1
    })
    A.equal(remote.x, 0)
end)

regression("validated-object-ids-and-transforms", function()
    local api = serverApi()
    local driver = api.addPlayer({ id = "driver", steamId = "steam-V", onlineId = 51 })
    api.addPlayer({ id = "observed-radius", steamId = "steam-R", onlineId = 52, x = -295 })
    api.addVehicle({ id = 501, driverId = "driver", x = 0 })
    local runtime = ServerRuntime.install(api, config())
    fireClient(api, Protocol.CHANNELS.vehicle, driver, {
        vehicleId = "501", transform = transform(0, 0), epoch = "vehicle-life", sequence = 1
    })
    A.equal(runtime.state.disabledCategories.vehicle, nil)
    api.clock.nowMs = 1100
    fireClient(api, Protocol.CHANNELS.vehicle, driver, {
        vehicleId = 999, transform = transform(0, 0), epoch = "vehicle-life", sequence = 2
    })
    A.equal(runtime.state.disabledCategories.vehicle, nil)
    api.clock.nowMs = 1200
    fireClient(api, Protocol.CHANNELS.vehicle, driver, {
        vehicleId = 501,
        transform = { x = 0, y = 0, z = 0, vx = 0, vy = 0, angularVelocity = 0, moving = false },
        epoch = "vehicle-life", sequence = 3
    })
    A.equal(#relays(api, Protocol.CHANNELS.vehicle), 0)
    api.clock.nowMs = 1300
    fireClient(api, Protocol.CHANNELS.vehicle, driver, {
        vehicleId = 501, transform = transform(20, 0), epoch = "vehicle-life", sequence = 4
    })
    local output = relays(api, Protocol.CHANNELS.vehicle)
    A.equal(#output, 1)
    A.equal(output[1].recipientOnlineId, 52)

    local failingApi = serverApi()
    local failingDriver = failingApi.addPlayer({ id = "driver", steamId = "steam-F", onlineId = 53 })
    failingApi.throwVehicleId = 998
    local failingRuntime = ServerRuntime.install(failingApi, config())
    fireClient(failingApi, Protocol.CHANNELS.vehicle, failingDriver, {
        vehicleId = 998, transform = transform(0, 0), epoch = "failure-life", sequence = 1
    })
    A.equal(failingRuntime.state.disabledCategories.vehicle, "adapter-error")

    local partsApi = serverApi()
    local partsDriver = partsApi.addPlayer({ id = "driver", steamId = "steam-parts", onlineId = 54 })
    partsApi.addVehicle({ id = 502, driverId = "driver" })
    function partsApi.getObservedVehicle() return nil end
    local partsRuntime = ServerRuntime.install(partsApi, config())
    fireClient(partsApi, Protocol.CHANNELS.parts, partsDriver, {
        vehicleId = 502,
        parts = { { partId = "hood", condition = 80, open = false } },
        epoch = "parts-life", sequence = 1
    })
    A.equal(partsRuntime.repair:pendingVehicleCount(), 0)
    A.equal(#relays(partsApi, Protocol.CHANNELS.parts), 0)
end)

regression("visual-parts-default-diff-and-linked-flow", function()
    local defaultApi = ClientRuntime.defaultApi()
    local function part(condition, opened, locked)
        local door = {}
        function door:isOpen() return opened end
        function door:isLocked() return locked end
        local value = {}
        function value:getCondition() return condition end
        function value:getDoor() return door end
        return value
    end
    local parts = {
        EngineDoor = part(80, false, true),
        TrunkDoor = part(70, true, false),
        DoorRearLeft = part(60, false, false),
        DoorRearRight = part(50, true, true)
    }
    local vehicle = {}
    function vehicle:getId() return 601 end
    function vehicle:getPartById(id) return parts[id] end
    local first = defaultApi.getDirtyVehicleParts(vehicle)
    A.equal(#first, 3)
    A.equal(#defaultApi.getDirtyVehicleParts(vehicle), 0)
    parts.EngineDoor = part(79, true, true)
    local changed = defaultApi.getDirtyVehicleParts(vehicle)
    A.equal(#changed, 1)
    A.equal(changed[1].partId, "hood")

    local sapi = serverApi()
    local serverPlayer = sapi.addPlayer({ id = "driver", steamId = "steam-linked", onlineId = 61 })
    sapi.addPlayer({ id = "near", steamId = "steam-linked-near", onlineId = 62, x = 1 })
    sapi.addVehicle({ id = 601, driverId = "driver" })
    local server = ServerRuntime.install(sapi, config())
    local capi = clientApi()
    local clientPlayer = capi.addPlayer({
        id = "driver", steamId = "steam-linked", onlineId = 61, localPlayer = true, vehicleId = 601
    })
    capi.addVehicle({ id = 601, driverId = "driver" })
    local emitted = false
    function capi.getDirtyVehicleParts()
        if emitted then return {} end
        emitted = true
        return { { partId = "hood", condition = 79, open = false } }
    end
    function capi.forwardClientCommand(moduleName, channel, payload)
        sapi.fire("OnClientCommand", moduleName, channel, serverPlayer, payload)
    end
    ClientRuntime.install(capi, config())
    capi.fire("OnPlayerUpdate", clientPlayer)
    A.equal(server.repair:pendingVehicleCount(), 1)
    A.equal(#relays(sapi, Protocol.CHANNELS.parts) > 0, true)
end)

regression("epoch-lifecycle-death-disconnect-and-ttl", function()
    local api = serverApi()
    local sender = api.addPlayer({ id = "actor", steamId = "steam-life", onlineId = 71 })
    api.addPlayer({ id = "near", steamId = "steam-life-near", onlineId = 72, x = 1 })
    api.addVehicle({ id = 701, driverId = "actor" })
    local runtime = ServerRuntime.install(api, config())
    fireClient(api, Protocol.CHANNELS.action, sender, {
        key = "read", phase = "start", progress = 0,
        anchor = { x = 0, y = 0, z = 0, facing = 0 }, animationVars = {},
        epoch = "life-one", sequence = 1
    })
    A.equal(runtime.state.actionPolicyByPlayerId["steam-life"].activeKey, "read")
    api.clock.nowMs = 1100
    fireClient(api, Protocol.CHANNELS.player, sender, {
        x = 0, y = 0, z = 0, facing = 0, latencyMs = 0,
        epoch = "arbitrary-change", sequence = 1
    })
    A.equal(#relays(api, Protocol.CHANNELS.player), 0)

    sender.onlineId = 73
    api.clock.nowMs = 1200
    fireClient(api, Protocol.CHANNELS.player, sender, {
        x = 0, y = 0, z = 0, facing = 0, latencyMs = 0,
        epoch = "life-two", sequence = 1
    })
    A.equal(runtime.state.actionPolicyByPlayerId["steam-life"], nil)
    A.equal(#relays(api, Protocol.CHANNELS.player) > 0, true)

    api.clock.nowMs = 1300
    fireClient(api, Protocol.CHANNELS.vehicle, sender, {
        vehicleId = 701, transform = transform(0, 0), epoch = "life-two", sequence = 1
    })
    sender.connected = false
    api.clock.nowMs = 200000
    api.fire("OnTick")
    A.equal(runtime.state.rateLimitByPlayerId["steam-life"], nil)
    A.equal(runtime.state.vehicleAuthorityById[701].epoch, 1)
    A.equal(runtime.state.vehiclePolicyById[701], nil)

    sender.connected = true
    sender.onlineId = 74
    api.clock.nowMs = 200100
    fireClient(api, Protocol.CHANNELS.player, sender, {
        x = 0, y = 0, z = 0, facing = 0, latencyMs = 0,
        epoch = "life-three", sequence = 1
    })
    A.equal(runtime.state.actorLifecycleByStableId["steam-life"].onlineId, 74)

    local capi = clientApi()
    local localPlayer = capi.addPlayer({
        id = "local", steamId = "steam-client", onlineId = 75, localPlayer = true,
        currentAction = {
            className = "ISReadABook", phase = "start", progress = 0,
            animationVars = { PerformingAction = true }
        }
    })
    local client = ClientRuntime.install(capi, config())
    capi.fire("OnPlayerUpdate", localPlayer)
    local epoch = client.epoch
    capi.fire("OnPlayerDeath", localPlayer)
    local actions = clientCommands(capi, Protocol.CHANNELS.action)
    A.equal(actions[#actions].args.phase, "cancel")
    A.equal(client.epoch, epoch)
    client.incomingSequences.example = { seq = 9 }
    client.adaptiveState.player = { latencyMs = 99 }
    client.disabledCategories.vehicle = "adapter-error"
    capi.fire("OnDisconnect")
    A.equal(client.epoch == epoch, false)
    A.equal(next(client.incomingSequences), nil)
    A.equal(next(client.adaptiveState), nil)
    A.equal(next(client.disabledCategories), nil)
end)

regression("post-action-grace-clears-and-suppresses", function()
    local api = clientApi()
    api.addPlayer({ id = "local", onlineId = 81, localPlayer = true })
    local remote = api.addPlayer({ id = "remote", onlineId = 82 })
    local runtime = ClientRuntime.install(api, config())
    api.fire("OnServerCommand", Protocol.NAMESPACE, Protocol.CHANNELS.action, {
        kind = "action", actorOnlineId = 82, key = "read", phase = "start",
        animationVars = { PerformingAction = true, IsReading = true, ReadType = "book" },
        timestampMs = 1000, epoch = "remote-life", sequence = 1
    })
    A.equal(remote.reading, true)
    A.equal(remote.animationVars.ReadType, "book")
    api.clock.nowMs = 1100
    api.fire("OnServerCommand", Protocol.NAMESPACE, Protocol.CHANNELS.action, {
        kind = "action", actorOnlineId = 82, key = "read", phase = "complete",
        animationVars = {}, timestampMs = 1100, graceUntilMs = 2350,
        epoch = "remote-life", sequence = 2
    })
    A.equal(remote.reading, false)
    A.equal(remote.animationVars.ReadType, nil)
    for sequence = 1, 4 do
        api.clock.nowMs = 1200 + sequence * 100
        api.fire("OnServerCommand", Protocol.NAMESPACE, Protocol.CHANNELS.player, {
            actorOnlineId = 82, x = 6, y = 0, z = 0, facing = 0, latencyMs = 0,
            epoch = "remote-move", sequence = sequence
        })
    end
    A.equal(remote.x, 0)
    for sequence = 5, 8 do
        api.clock.nowMs = 2400 + sequence * 100
        api.fire("OnServerCommand", Protocol.NAMESPACE, Protocol.CHANNELS.player, {
            actorOnlineId = 82, x = 6, y = 0, z = 0, facing = 0, latencyMs = 0,
            epoch = "remote-move", sequence = sequence
        })
    end
    A.equal(remote.x, 0)
end)

-- Break caught: replaying only generic animation variables for reading instead of the
-- B42 reading semantic state, which leaves different observers with different arm poses.
regression("reading-uses-semantic-observer-state", function()
    local api = clientApi()
    api.addPlayer({ id = "local", onlineId = 181, localPlayer = true })
    local remote = api.addPlayer({ id = "reader", onlineId = 182 })
    ClientRuntime.install(api, config())

    api.fire("OnServerCommand", Protocol.NAMESPACE, Protocol.CHANNELS.action, {
        kind = "action", actorOnlineId = 182, key = "read", phase = "start",
        animationVars = { PerformingAction = true, IsReading = true, ReadType = "book" },
        timestampMs = 1000, epoch = "reader-life", sequence = 1
    })
    A.equal(remote.reading, true)
    A.equal(remote.animationVars.ReadType, "book")
    A.equal(remote.animationVars.PerformingAction, nil)
    A.equal(remote.animationVars.IsReading, nil)
    A.equal(#remote.reportedEvents, 1)
    A.equal(remote.reportedEvents[1], "EventRead")

    api.clock.nowMs = 1500
    api.fire("OnServerCommand", Protocol.NAMESPACE, Protocol.CHANNELS.action, {
        kind = "action", actorOnlineId = 182, key = "read", phase = "progress",
        animationVars = { ReadType = "book" }, progress = 0.5,
        timestampMs = 1500, epoch = "reader-life", sequence = 2
    })
    A.equal(#remote.reportedEvents, 1)

    api.clock.nowMs = 2000
    api.fire("OnServerCommand", Protocol.NAMESPACE, Protocol.CHANNELS.action, {
        kind = "action", actorOnlineId = 182, key = "read", phase = "complete",
        animationVars = {}, timestampMs = 2000, graceUntilMs = 3250,
        epoch = "reader-life", sequence = 3
    })
    A.equal(remote.reading, false)
    A.equal(remote.animationVars.ReadType, nil)
end)

regression("diagnostics-install-idempotence-and-render-reason", function()
    local api = clientApi()
    local localPlayer = api.addPlayer({ id = "local", onlineId = 91, localPlayer = true })
    local first = ClientRuntime.install(api, config())
    local second = ClientRuntime.install(api, config())
    A.equal(first, second)
    A.equal(#api.events.OnServerCommand, 1)
    api.fire("OnServerCommand", Protocol.NAMESPACE, Protocol.CHANNELS.action, {
        kind = "action", actorOnlineId = 91, key = "read", phase = "start",
        animationVars = { PerformingAction = true }, epoch = "local-relay", sequence = 1
    })
    A.equal(first.diagnostics.rejected["local-player"], 1)
    A.equal(first.diagnostics.rejected.missing, nil)
    A.equal(localPlayer.animationVars.PerformingAction, nil)

    local incomplete = clientApi()
    incomplete.events.OnDisconnect = nil
    local partial = ClientRuntime.install(incomplete, config())
    A.equal(partial.diagnostics.disabledAdapters["event:OnDisconnect"], "event-unavailable")

    local noClientEvents = ClientRuntime.install({ nowMs = function() return 0 end }, config())
    A.equal(noClientEvents.diagnostics.disabledAdapters["event:OnServerCommand"], "event-unavailable")
    local noServerEvents = ServerRuntime.install({ nowMs = function() return 0 end }, config())
    A.equal(noServerEvents.diagnostics.disabledAdapters["event:OnClientCommand"], "event-unavailable")

    local summary = {
        accepted = { player = 3 }, rejected = { stale = 2 }, corrections = 1,
        fallbacks = 4, disabledAdapters = { vehicle = "adapter-error" }
    }
    local clientLine = ClientRuntime.formatDiagnostics(summary)
    local serverLine = ServerRuntime.formatDiagnostics(summary)
    for _, line in ipairs({ clientLine, serverLine }) do
        A.equal(line:find("accepted=player:3", 1, true) ~= nil, true)
        A.equal(line:find("rejected=stale:2", 1, true) ~= nil, true)
        A.equal(line:find("corrections=1", 1, true) ~= nil, true)
        A.equal(line:find("fallbacks=4", 1, true) ~= nil, true)
        A.equal(line:find("disabled=vehicle:adapter-error", 1, true) ~= nil, true)
    end
end)

if #failures > 0 then
    error("runtime round1 regressions failed: " .. tostring(#failures) .. "\n" .. table.concat(failures, "\n"))
end

print("PASS runtime round1")
