local A = require("tests.support.assertions")
local Config = require("ApolloMPSync/Config")
local FakePZ = require("tests.support.fake_pz")
local Protocol = require("ApolloMPSync/Protocol")
local ServerRuntime = require("ApolloMPSync/ServerRuntime")

local failures = {}

local function regression(name, body)
    local ok, reason = pcall(body)
    if not ok then
        failures[#failures + 1] = name .. ": " .. tostring(reason)
        print("RED runtime round3 " .. name .. ": " .. tostring(reason))
    end
end

local function transform(x)
    return {
        x = x, y = 0, z = 0, angle = 0,
        vx = 0, vy = 0, angularVelocity = 0, moving = false
    }
end

local function serverApi()
    local api = FakePZ.new()
    api.events = { OnClientCommand = {}, OnTick = {} }
    api.serverCommands = {}
    function api.addEvent(name, handler)
        local handlers = api.events[name]
        if handlers == nil then return false end
        handlers[#handlers + 1] = handler
        return true
    end
    function api.fire(name, ...)
        for _, handler in ipairs(api.events[name] or {}) do handler(...) end
    end
    function api.getStablePlayerId(player) return player and player.steamId or nil end
    function api.getPlayerOnlineId(player) return player and player.onlineId or nil end
    function api.getObservedPlayer(player)
        return player and { id = player.steamId, x = player.x, y = player.y, z = player.z } or nil
    end
    function api.getOnlinePlayers()
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
    function api.applyVehiclePart() return true end
    return api
end

local function installRuntime(api)
    return ServerRuntime.install(api, Config.defaults())
end

local function fireClient(api, channel, sender, payload)
    api.fire("OnClientCommand", Protocol.NAMESPACE, channel, sender, payload)
end

local function commands(api, channel)
    local result = {}
    for _, command in ipairs(api.serverCommands) do
        if command.command == channel then result[#result + 1] = command end
    end
    return result
end

regression("expired-trailer-relation-rejects-stale-detach", function()
    local api = serverApi()
    local driver = api.addPlayer({ id = "driver", steamId = "steam-relation", onlineId = 201 })
    api.addPlayer({ id = "near", steamId = "steam-near", onlineId = 202, x = 1 })
    local towing = api.addVehicle({ id = 2001, driverId = "driver", trailerId = 2002 })
    local trailer = api.addVehicle({ id = 2002, towingVehicleId = 2001 })
    local runtime = installRuntime(api)
    fireClient(api, Protocol.CHANNELS.trailer, driver, {
        operation = "attach", towingVehicleId = 2001, trailerId = 2002,
        transform = transform(0), epoch = "relation-life", sequence = 1
    })
    A.equal(runtime.state.trailerRelationById[2002], 2001)
    A.equal(#commands(api, Protocol.CHANNELS.trailer), 1)

    towing.trailerId = nil
    trailer.towingVehicleId = nil
    api.clock.nowMs = 181000
    api.fire("OnTick")
    A.equal(runtime.state.trailerRelationById[2002], nil)
    A.equal(runtime.state.trailerRelationLastSeenById[2002], nil)
    A.equal(runtime.state.trailerAuthorityById[2002].epoch, 1)
    A.equal(runtime.state.objectSequences[Protocol.CHANNELS.trailer .. ":2002"], 1)

    api.clock.nowMs = 181100
    fireClient(api, Protocol.CHANNELS.trailer, driver, {
        operation = "detach", towingVehicleId = 2001, trailerId = 2002,
        transform = transform(0), epoch = "relation-life", sequence = 2
    })
    A.equal(#commands(api, Protocol.CHANNELS.trailer), 1)
    A.equal(runtime.diagnostics.rejected.relation, 1)
end)

regression("post-prune-vehicle-report-recreates-transient-policy", function()
    local api = serverApi()
    local driver = api.addPlayer({ id = "driver", steamId = "steam-vehicle", onlineId = 211 })
    api.addPlayer({ id = "near", steamId = "steam-near", onlineId = 212, x = 1 })
    api.addVehicle({ id = 2101, driverId = "driver" })
    local runtime = installRuntime(api)
    fireClient(api, Protocol.CHANNELS.vehicle, driver, {
        vehicleId = 2101, transform = transform(0),
        epoch = "vehicle-life", sequence = 1
    })
    A.equal(#commands(api, Protocol.CHANNELS.vehicle), 1)
    A.equal(runtime.state.objectSequences[Protocol.CHANNELS.vehicle .. ":2101"], 1)

    api.clock.nowMs = 181000
    api.fire("OnTick")
    A.equal(runtime.state.vehiclePolicyById[2101], nil)
    A.equal(runtime.state.vehicleAuthorityById[2101].epoch, 1)

    api.clock.nowMs = 181100
    fireClient(api, Protocol.CHANNELS.vehicle, driver, {
        vehicleId = 2101, transform = transform(1),
        epoch = "vehicle-life", sequence = 2
    })
    local relays = commands(api, Protocol.CHANNELS.vehicle)
    A.equal(#relays, 2)
    A.equal(relays[2].args.sequence, 2)
    A.equal(relays[2].args.epoch, 1)
    A.equal(runtime.state.vehiclePolicyById[2101] ~= nil, true)
end)

if #failures > 0 then
    error("runtime round3 regressions failed: " .. tostring(#failures) .. "\n" .. table.concat(failures, "\n"))
end

print("PASS runtime round3")
