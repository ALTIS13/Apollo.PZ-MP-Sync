local A = require("tests.support.assertions")
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
        print("RED runtime round2 " .. name .. ": " .. tostring(reason))
    end
end

local function transform(x, y)
    return {
        x = x, y = y, z = 0, angle = 0,
        vx = 0, vy = 0, angularVelocity = 0, moving = false
    }
end

local function installEvents(api, names)
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

local function serverApi()
    local api = FakePZ.new()
    installEvents(api, { "OnClientCommand", "OnTick" })
    api.serverCommands = {}
    api.rosterReads = 0
    function api.getStablePlayerId(player) return player and player.steamId or nil end
    function api.getPlayerOnlineId(player) return player and player.onlineId or nil end
    function api.getObservedPlayer(player)
        return player and { id = player.steamId, x = player.x, y = player.y, z = player.z } or nil
    end
    function api.getOnlinePlayers()
        api.rosterReads = api.rosterReads + 1
        local players = {}
        for _, player in pairs(api.players) do
            if player.connected ~= false then players[#players + 1] = player end
        end
        return players
    end
    function api.getVehicleById(id) return api.vehicles[id] end
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
            recipientOnlineId = player.onlineId, module = moduleName,
            command = channel, args = payload
        }
    end
    function api.applyVehiclePart(vehicle, partId, condition)
        vehicle.appliedParts = vehicle.appliedParts or {}
        vehicle.appliedParts[partId] = condition
        return true
    end
    return api
end

local function clientApi()
    local api = FakePZ.new()
    installEvents(api, {
        "OnPlayerUpdate", "OnServerCommand", "OnPlayerDeath", "OnDisconnect",
        "OnEnterVehicle", "OnExitVehicle", "OnSwitchVehicleSeat"
    })
    api.clientCommands = {}
    function api.sendClientCommand(moduleName, channel, payload)
        api.clientCommands[#api.clientCommands + 1] = {
            module = moduleName, command = channel, args = payload
        }
        if api.forwardClientCommand then api.forwardClientCommand(moduleName, channel, payload) end
    end
    function api.getPlayerByOnlineId(onlineId)
        for _, player in pairs(api.players) do
            if player.onlineId == onlineId then return player end
        end
    end
    function api.getVehicleById(id) return api.vehicles[id] end
    function api.getLocalPlayer()
        for _, player in pairs(api.players) do if player.localPlayer then return player end end
    end
    function api.getObservedActionClasses() return {} end
    return api
end

local function config(overrides)
    local value = Config.defaults()
    for key, entry in pairs(overrides or {}) do value[key] = entry end
    return value
end

local function fireClient(api, channel, sender, payload)
    api.fire("OnClientCommand", Protocol.NAMESPACE, channel, sender, payload)
end

local function commands(api, channel, source)
    local result = {}
    for _, command in ipairs(source or api.serverCommands or api.clientCommands) do
        if command.command == channel then result[#result + 1] = command end
    end
    return result
end

regression("detach-after-observed-relation-clears", function()
    local sapi = serverApi()
    local serverDriver = sapi.addPlayer({ id = "driver", steamId = "steam-detach", onlineId = 101 })
    sapi.addPlayer({ id = "near", steamId = "steam-near", onlineId = 102, x = 2 })
    local towing = sapi.addVehicle({ id = 1001, driverId = "driver", trailerId = 1002 })
    local trailer = sapi.addVehicle({ id = 1002, towingVehicleId = 1001, x = 1 })
    local server = ServerRuntime.install(sapi, config())

    local capi = clientApi()
    local clientDriver = capi.addPlayer({
        id = "driver", steamId = "steam-detach", onlineId = 101,
        localPlayer = true, vehicleId = 1001
    })
    local clientTowing = capi.addVehicle({ id = 1001, driverId = "driver", trailerId = 1002 })
    local clientTrailer = capi.addVehicle({ id = 1002, towingVehicleId = 1001, x = 1 })
    function capi.forwardClientCommand(moduleName, channel, payload)
        sapi.fire("OnClientCommand", moduleName, channel, serverDriver, payload)
    end
    ClientRuntime.install(capi, config())
    capi.fire("OnPlayerUpdate", clientDriver)
    A.equal(#commands(sapi, Protocol.CHANNELS.trailer), 1)

    towing.trailerId = nil
    trailer.towingVehicleId = nil
    clientTowing.trailerId = nil
    clientTrailer.towingVehicleId = nil
    capi.clock.nowMs = 1200
    sapi.clock.nowMs = 1200
    capi.fire("OnPlayerUpdate", clientDriver)
    local relays = commands(sapi, Protocol.CHANNELS.trailer)
    A.equal(#relays, 2)
    A.equal(relays[2].args.operation, "detach")
    A.equal(server.state.trailerRelationById[1002], nil)
end)

regression("trailer-driver-epoch-and-process-high-water", function()
    local api = serverApi()
    local driverA = api.addPlayer({ id = "driver-a", steamId = "steam-A", onlineId = 111 })
    local driverB = api.addPlayer({ id = "driver-b", steamId = "steam-B", onlineId = 112 })
    api.addPlayer({ id = "near", steamId = "steam-near", onlineId = 113, x = 1 })
    local towing = api.addVehicle({ id = 1101, driverId = "driver-a", trailerId = 1102 })
    api.addVehicle({ id = 1102, towingVehicleId = 1101 })
    local runtime = ServerRuntime.install(api, config())
    fireClient(api, Protocol.CHANNELS.trailer, driverA, {
        operation = "attach", towingVehicleId = 1101, trailerId = 1102,
        transform = transform(0, 0), epoch = "life-A", sequence = 1
    })
    towing.driverId = "driver-b"
    api.clock.nowMs = 1200
    fireClient(api, Protocol.CHANNELS.trailer, driverB, {
        operation = "attach", towingVehicleId = 1101, trailerId = 1102,
        transform = transform(0, 0), epoch = "life-B", sequence = 1
    })
    local relays = commands(api, Protocol.CHANNELS.trailer)
    A.equal(relays[#relays].args.driverOnlineId, 112)
    A.equal(relays[#relays].args.epoch, 2)
    A.equal(relays[#relays].args.sequence, 2)

    api.clock.nowMs = 200000
    api.fire("OnTick")
    A.equal(runtime.state.trailerAuthorityById[1102].epoch, 2)
    A.equal(runtime.state.objectSequences[Protocol.CHANNELS.trailer .. ":1102"], 2)
    A.equal(runtime.state.trailerPolicyById.trailers.trailers["1102"], nil)
    A.equal(runtime.state.trailerPolicyById.trailers.towChains["1101"], nil)
end)

regression("bounded-multipart-linked-update", function()
    local sapi = serverApi()
    local serverDriver = sapi.addPlayer({ id = "driver", steamId = "steam-parts", onlineId = 121 })
    sapi.addPlayer({ id = "near", steamId = "steam-near", onlineId = 122, x = 1 })
    sapi.addVehicle({ id = 1201, driverId = "driver" })
    local server = ServerRuntime.install(sapi, config())

    local capi = clientApi()
    local clientDriver = capi.addPlayer({
        id = "driver", steamId = "steam-parts", onlineId = 121,
        localPlayer = true, vehicleId = 1201
    })
    capi.addVehicle({ id = 1201, driverId = "driver" })
    local emitted = false
    function capi.getDirtyVehicleParts()
        if emitted then return {} end
        emitted = true
        return {
            { partId = "hood", condition = 81, open = false, locked = true },
            { partId = "trunk", condition = 72, open = true, locked = false },
            { partId = "rear-door", condition = 63, open = false, locked = false }
        }
    end
    function capi.forwardClientCommand(moduleName, channel, payload)
        sapi.fire("OnClientCommand", moduleName, channel, serverDriver, payload)
    end
    ClientRuntime.install(capi, config())
    capi.fire("OnPlayerUpdate", clientDriver)
    local sent = commands(capi, Protocol.CHANNELS.parts)
    local relays = commands(sapi, Protocol.CHANNELS.parts)
    A.equal(#sent, 1)
    A.equal(#sent[1].args.parts, 3)
    A.equal(#relays, 1)
    A.equal(#relays[1].args.parts, 3)
    A.equal(server.repair:pendingVehicleCount(), 1)
    local queued = server.repair.queuedByVehicleId[1201].parts
    A.equal(queued.hood, 81)
    A.equal(queued.trunk, 72)
    A.equal(queued["rear-door"], 63)

    local invalidApi = serverApi()
    local invalidDriver = invalidApi.addPlayer({ id = "driver", steamId = "steam-invalid", onlineId = 123 })
    invalidApi.addVehicle({ id = 1202, driverId = "driver" })
    local invalidRuntime = ServerRuntime.install(invalidApi, config())
    fireClient(invalidApi, Protocol.CHANNELS.parts, invalidDriver, {
        vehicleId = 1202,
        parts = {
            { partId = "hood", condition = 80, open = false },
            { partId = "engine", condition = 90, open = true }
        },
        epoch = "invalid-life", sequence = 1
    })
    A.equal(invalidRuntime.repair:pendingVehicleCount(), 0)
    A.equal(#commands(invalidApi, Protocol.CHANNELS.parts), 0)
end)

regression("death-cancel-has-terminal-rate-bucket", function()
    local sapi = serverApi()
    local serverPlayer = sapi.addPlayer({ id = "actor", steamId = "steam-action", onlineId = 131 })
    sapi.addPlayer({ id = "near", steamId = "steam-near", onlineId = 132, x = 1 })
    local server = ServerRuntime.install(sapi, config())

    local capi = clientApi()
    local clientPlayer = capi.addPlayer({
        id = "actor", steamId = "steam-action", onlineId = 131, localPlayer = true,
        currentAction = {
            className = "ISReadABook", phase = "start", progress = 0,
            animationVars = { PerformingAction = true, IsReading = true }
        }
    })
    function capi.forwardClientCommand(moduleName, channel, payload)
        sapi.fire("OnClientCommand", moduleName, channel, serverPlayer, payload)
    end
    ClientRuntime.install(capi, config())
    capi.fire("OnPlayerUpdate", clientPlayer)
    A.equal(server.state.actionPolicyByPlayerId["steam-action"].activeKey, "read")
    capi.clock.nowMs = 1050
    sapi.clock.nowMs = 1050
    capi.fire("OnPlayerDeath", clientPlayer)
    A.equal(server.state.actionPolicyByPlayerId["steam-action"].activeKey, nil)
    A.equal(#commands(sapi, Protocol.CHANNELS.action), 2)
end)

regression("visual-cache-reset-and-bounded-prune-cadence", function()
    local defaultApi = ClientRuntime.defaultApi()
    local function part(condition, opened)
        local door = {}
        function door:isOpen() return opened end
        function door:isLocked() return false end
        local value = {}
        function value:getCondition() return condition end
        function value:getDoor() return door end
        return value
    end
    local vehicle = { parts = {
        EngineDoor = part(80, false), TrunkDoor = part(70, true),
        DoorRearLeft = part(60, false), DoorRearRight = part(50, true)
    } }
    function vehicle:getId() return 1401 end
    function vehicle:getPartById(id) return self.parts[id] end
    A.equal(#defaultApi.getDirtyVehicleParts(vehicle), 3)
    A.equal(#defaultApi.getDirtyVehicleParts(vehicle), 0)
    local client = ClientRuntime.new(defaultApi, config())
    client:resetLocalState(true)
    A.equal(#defaultApi.getDirtyVehicleParts(vehicle), 3)

    local api = serverApi()
    local runtime = ServerRuntime.install(api, config())
    A.equal(runtime.nextPruneMs, 61000)
    api.clock.nowMs = 2000
    api.fire("OnTick")
    api.clock.nowMs = 60000
    api.fire("OnTick")
    A.equal(api.rosterReads, 0)
    api.clock.nowMs = 61000
    api.fire("OnTick")
    A.equal(api.rosterReads, 1)
    A.equal(runtime.nextPruneMs, 121000)
    api.clock.nowMs = 61001
    api.fire("OnTick")
    A.equal(api.rosterReads, 1)
end)

if #failures > 0 then
    error("runtime round2 regressions failed: " .. tostring(#failures) .. "\n" .. table.concat(failures, "\n"))
end

print("PASS runtime round2")
