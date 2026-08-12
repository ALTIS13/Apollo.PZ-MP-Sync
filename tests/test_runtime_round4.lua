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
        print("RED runtime round4 " .. name .. ": " .. tostring(reason))
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

local function fireClient(api, channel, sender, payload)
    api.fire("OnClientCommand", Protocol.NAMESPACE, channel, sender, payload)
end

local function trailerCommands(api)
    local result = {}
    for _, command in ipairs(api.serverCommands) do
        if command.command == Protocol.CHANNELS.trailer then
            result[#result + 1] = command
        end
    end
    return result
end

local function commands(api, channel)
    local result = {}
    for _, command in ipairs(api.serverCommands) do
        if command.command == channel then result[#result + 1] = command end
    end
    return result
end

-- Break caught: expiring a legitimate unchanged tow because the client does not resend it.
regression("ttl-revalidates-observed-relation-before-real-detach", function()
    local api = serverApi()
    local driver = api.addPlayer({ id = "driver", steamId = "steam-retain", onlineId = 221 })
    api.addPlayer({ id = "near", steamId = "steam-near", onlineId = 222, x = 1 })
    local towing = api.addVehicle({ id = 2201, driverId = "driver", trailerId = 2202 })
    local trailer = api.addVehicle({ id = 2202, towingVehicleId = 2201 })
    local runtime = ServerRuntime.install(api, Config.defaults())

    fireClient(api, Protocol.CHANNELS.trailer, driver, {
        operation = "attach", towingVehicleId = 2201, trailerId = 2202,
        transform = transform(0), epoch = "retain-life", sequence = 1
    })
    A.equal(runtime.state.trailerRelationById[2202], 2201)
    A.equal(runtime.state.trailerRelationLastSeenById[2202], 1000)
    A.equal(#trailerCommands(api), 1)

    api.clock.nowMs = 181000
    api.fire("OnTick")
    A.equal(runtime.state.trailerRelationById[2202], 2201)
    A.equal(runtime.state.trailerRelationLastSeenById[2202], 181000)
    A.equal(runtime.state.trailerAuthorityById[2202].epoch, 1)
    A.equal(runtime.state.objectSequences[Protocol.CHANNELS.trailer .. ":2202"], 1)

    towing.trailerId = nil
    trailer.towingVehicleId = nil
    api.clock.nowMs = 181100
    fireClient(api, Protocol.CHANNELS.trailer, driver, {
        operation = "detach", towingVehicleId = 2201, trailerId = 2202,
        transform = transform(0), epoch = "retain-life", sequence = 2
    })
    local relays = trailerCommands(api)
    A.equal(#relays, 2)
    A.equal(relays[2].args.operation, "detach")
    A.equal(runtime.state.trailerRelationById[2202], nil)
    A.equal(runtime.state.trailerAuthorityById[2202].epoch, 1)
    A.equal(runtime.state.objectSequences[Protocol.CHANNELS.trailer .. ":2202"], 2)
end)

local expiredRelationCases = {
    {
        name = "absent",
        arrangeExpiry = function(api, towing, trailer)
            towing.trailerId = nil
            trailer.towingVehicleId = nil
        end,
        rejection = "relation"
    },
    {
        name = "different",
        arrangeExpiry = function(api, towing, trailer)
            local replacement = api.addVehicle({ id = 2203, driverId = "driver", trailerId = 2202 })
            towing.trailerId = nil
            trailer.towingVehicleId = replacement.id
        end,
        rejection = "relation"
    },
    {
        name = "missing-trailer",
        arrangeExpiry = function(api, towing, trailer)
            towing.trailerId = nil
            api.vehicles[trailer.id] = nil
        end,
        rejection = "vehicle"
    },
    {
        name = "invalid-towing-id",
        arrangeExpiry = function(api, towing)
            towing.id = "invalid"
        end,
        rejection = "relation"
    }
}

for _, case in ipairs(expiredRelationCases) do
    -- Break caught: retaining cached client relation history without matching server observation.
    regression("ttl-clears-" .. case.name .. "-relation-and-rejects-stale-detach", function()
        local api = serverApi()
        local driver = api.addPlayer({ id = "driver", steamId = "steam-clear", onlineId = 231 })
        api.addPlayer({ id = "near", steamId = "steam-near", onlineId = 232, x = 1 })
        local towing = api.addVehicle({ id = 2201, driverId = "driver", trailerId = 2202 })
        local trailer = api.addVehicle({ id = 2202, towingVehicleId = 2201 })
        local runtime = ServerRuntime.install(api, Config.defaults())

        fireClient(api, Protocol.CHANNELS.trailer, driver, {
            operation = "attach", towingVehicleId = 2201, trailerId = 2202,
            transform = transform(0), epoch = "clear-life", sequence = 1
        })
        A.equal(runtime.state.trailerRelationById[2202], 2201)
        A.equal(#trailerCommands(api), 1)

        case.arrangeExpiry(api, towing, trailer)
        api.clock.nowMs = 181000
        api.fire("OnTick")
        A.equal(runtime.state.trailerRelationById[2202], nil)
        A.equal(runtime.state.trailerRelationLastSeenById[2202], nil)
        A.equal(runtime.state.trailerAuthorityById[2202].epoch, 1)
        A.equal(runtime.state.objectSequences[Protocol.CHANNELS.trailer .. ":2202"], 1)

        api.clock.nowMs = 181100
        fireClient(api, Protocol.CHANNELS.trailer, driver, {
            operation = "detach", towingVehicleId = 2201, trailerId = 2202,
            transform = transform(0), epoch = "clear-life", sequence = 2
        })
        A.equal(#trailerCommands(api), 1)
        A.equal(runtime.diagnostics.rejected[case.rejection], 1)
        A.equal(runtime.state.trailerAuthorityById[2202].epoch, 1)
        A.equal(runtime.state.objectSequences[Protocol.CHANNELS.trailer .. ":2202"], 1)
    end)
end

-- Break caught: allowing a background adapter exception to escape, retain relation
-- authority, or disable unrelated player traffic.
regression("ttl-revalidation-adapter-exception-fails-closed-category-locally", function()
    local api = serverApi()
    local driver = api.addPlayer({ id = "driver", steamId = "steam-error", onlineId = 241 })
    api.addPlayer({ id = "near", steamId = "steam-near", onlineId = 242, x = 1 })
    api.addVehicle({ id = 2401, driverId = "driver", trailerId = 2402 })
    api.addVehicle({ id = 2402, towingVehicleId = 2401 })
    local runtime = ServerRuntime.install(api, Config.defaults())

    fireClient(api, Protocol.CHANNELS.trailer, driver, {
        operation = "attach", towingVehicleId = 2401, trailerId = 2402,
        transform = transform(0), epoch = "error-life", sequence = 1
    })
    A.equal(#trailerCommands(api), 1)

    function api.getVehicleTowedBy()
        error("fake inverse tow adapter failure")
    end
    api.clock.nowMs = 181000
    api.fire("OnTick")
    A.equal(runtime.state.trailerRelationById[2402], nil)
    A.equal(runtime.state.trailerRelationLastSeenById[2402], nil)
    A.equal(runtime.state.trailerAuthorityById[2402].epoch, 1)
    A.equal(runtime.state.objectSequences[Protocol.CHANNELS.trailer .. ":2402"], 1)
    A.equal(runtime.state.disabledCategories[Protocol.CHANNELS.trailer], "adapter-error")
    A.equal(runtime.diagnostics.disabledAdapters[Protocol.CHANNELS.trailer], "adapter-error")

    api.clock.nowMs = 181100
    fireClient(api, Protocol.CHANNELS.trailer, driver, {
        operation = "attach", towingVehicleId = 2401, trailerId = 2402,
        transform = transform(0), epoch = "error-life", sequence = 2
    })
    A.equal(#trailerCommands(api), 1)
    A.equal(runtime.state.trailerRelationById[2402], nil)

    api.clock.nowMs = 181200
    fireClient(api, Protocol.CHANNELS.player, driver, {
        x = 0, y = 0, z = 0, facing = 0, latencyMs = 0,
        epoch = "error-life", sequence = 1
    })
    A.equal(#commands(api, Protocol.CHANNELS.player), 1)
    A.equal(runtime.state.disabledCategories[Protocol.CHANNELS.player], nil)
end)

if #failures > 0 then
    error("runtime round4 regressions failed: " .. tostring(#failures) .. "\n" .. table.concat(failures, "\n"))
end

print("PASS runtime round4")
