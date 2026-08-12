local A = require("tests.support.assertions")
local FakePZ = require("tests.support.fake_pz")
local ActionRegistry = require("ApolloMPSync/ActionRegistry")
local StateSampler = require("ApolloMPSync/StateSampler")
local RemoteRenderer = require("ApolloMPSync/RemoteRenderer")
local ServerState = require("ApolloMPSync/ServerState")

local function transform(x, y)
    return {
        x = x,
        y = y,
        z = 0,
        angle = 45,
        vx = 2,
        vy = 3,
        angularVelocity = 4,
        moving = true
    }
end

local function assertPlain(value, seen)
    local valueType = type(value)
    if valueType ~= "table" then
        A.equal(valueType == "nil" or valueType == "string" or valueType == "number" or valueType == "boolean", true)
        return
    end

    seen = seen or {}
    A.equal(seen[value], nil)
    seen[value] = true
    for key, entry in pairs(value) do
        assertPlain(key, seen)
        assertPlain(entry, seen)
    end
    seen[value] = nil
end

local api = FakePZ.new()
local localPlayer = api.addPlayer({
    id = "local-player",
    x = 10,
    y = 20,
    z = 1,
    facing = 90,
    pingMs = 135,
    localPlayer = true,
    vehicleId = "car-local",
    currentAction = {
        className = "ISClimbThroughWindow",
        phase = "progress",
        progress = 0.5,
        sequence = 7,
        animationVars = { PerformingAction = true, ClimbWindow = true, DoAnything = true }
    }
})
local remotePlayer = api.addPlayer({ id = "remote-player", x = 4, y = 5, z = 0, facing = 0 })
local localVehicle = api.addVehicle({
    id = "car-local",
    driverId = "local-player",
    x = 10,
    y = 20,
    z = 1,
    angle = 45,
    vx = 2,
    vy = 3,
    angularVelocity = 4,
    moving = true,
    trailerId = "trailer-1"
})
local trailer = api.addVehicle({ id = "trailer-1", towingVehicleId = "car-local", x = 8, y = 20 })
local remoteVehicle = api.addVehicle({ id = "car-remote", driverId = "remote-player", trailerId = "trailer-remote" })
local remoteTrailer = api.addVehicle({ id = "trailer-remote", towingVehicleId = "car-remote", x = 4, y = 5 })

-- Break caught: leaking the API's ping spelling or a PZ player/action handle into a player report.
local playerSnapshot, playerReason = StateSampler.player(api, localPlayer)
A.equal(playerReason, "ok")
A.equal(playerSnapshot.id, "local-player")
A.equal(playerSnapshot.latencyMs, 135)
A.equal(playerSnapshot.pingMs, nil)
A.equal(playerSnapshot.action, "climb-window")
A.equal(playerSnapshot.player, nil)
assertPlain(playerSnapshot)

-- Break caught: emitting an unregistered action name or animation variable from the fake game object.
local actionSnapshot, actionReason = StateSampler.action(api, localPlayer)
A.equal(actionReason, "ok")
A.equal(actionSnapshot.key, "climb-window")
A.equal(actionSnapshot.category, "climb-window")
A.equal(actionSnapshot.animationVars.PerformingAction, true)
A.equal(actionSnapshot.animationVars.ClimbWindow, true)
A.equal(actionSnapshot.animationVars.DoAnything, nil)
A.equal(actionSnapshot.anchor.facing, 90)
A.equal(actionSnapshot.progress, 0.5)
A.equal(actionSnapshot.sequence, 7)
A.equal(actionSnapshot.timestampMs, 1000)
assertPlain(actionSnapshot)

-- Break caught: retaining fake vehicle/trailer objects rather than copying their identity and transform fields.
local vehicleSnapshot, vehicleReason = StateSampler.vehicle(api, localPlayer)
A.equal(vehicleReason, "ok")
A.equal(vehicleSnapshot.vehicleId, "car-local")
A.equal(vehicleSnapshot.driverId, "local-player")
A.equal(vehicleSnapshot.transform.x, 10)
A.equal(vehicleSnapshot.vehicle, nil)
assertPlain(vehicleSnapshot)
local trailerSnapshot, trailerReason = StateSampler.trailer(api, localVehicle)
A.equal(trailerReason, "ok")
A.equal(trailerSnapshot.trailerId, "trailer-1")
A.equal(trailerSnapshot.towingVehicleId, "car-local")
A.equal(trailerSnapshot.transform.x, 8)
A.equal(trailerSnapshot.trailer, nil)
assertPlain(trailerSnapshot)

-- Break caught: a missing Project Zomboid method throwing through any sampler category.
for _, case in ipairs({
    { "player", {} },
    { "action", {} },
    { "vehicle", {} },
    { "trailer", {} }
}) do
    local ok, result, reason = pcall(StateSampler[case[1]], case[2], localPlayer)
    A.equal(ok, true)
    A.equal(result, nil)
    A.equal(reason, "api-unavailable")
end

-- Break caught: restoring any player pose renderer for local or remote players.
local localRender, localRenderReason = RemoteRenderer.apply(api, localPlayer, {
    kind = "player",
    pose = { x = 100, y = 200, z = 2, facing = 180 }
})
A.equal(localRender, nil)
A.equal(localRenderReason, "native-authority")
A.equal(localPlayer.x, 10)
A.equal(localPlayer.y, 20)

-- Remote player coordinates remain native-authoritative too.
local rendered, renderReason = RemoteRenderer.apply(api, remotePlayer, {
    kind = "player",
    pose = { x = 6, y = 7, z = 1, facing = 270, inventory = "forbidden" }
})
A.equal(rendered, nil)
A.equal(renderReason, "native-authority")
A.equal(remotePlayer.x, 4)
A.equal(remotePlayer.y, 5)
A.equal(remotePlayer.z, 0)
A.equal(remotePlayer.facing, 0)
A.equal(remotePlayer.inventory, nil)

-- Break caught: a relayed action changing animation state on the locally controlled player.
localPlayer.animationVars.PerformingAction = false
local localActionRendered, localActionReason = RemoteRenderer.apply(api, localPlayer, {
    kind = "action",
    key = "read",
    animationVars = { PerformingAction = true }
})
A.equal(localActionRendered, nil)
A.equal(localActionReason, "local-player")
A.equal(localPlayer.animationVars.PerformingAction, false)

-- Break caught: rendering an action when local-player authority cannot be established.
local unavailableAuthorityApi = FakePZ.new()
local unavailableAuthorityPlayer = unavailableAuthorityApi.addPlayer({ id = "unknown-authority" })
unavailableAuthorityApi.isLocalPlayer = nil
local authorityOk, authorityResult, authorityReason = pcall(RemoteRenderer.apply,
    unavailableAuthorityApi, unavailableAuthorityPlayer, {
        kind = "action",
        key = "read",
        animationVars = { PerformingAction = true }
    })
A.equal(authorityOk, true)
A.equal(authorityResult, nil)
A.equal(authorityReason, "api-unavailable")
A.equal(unavailableAuthorityPlayer.animationVars.PerformingAction, nil)

-- Break caught: using generic partial variables for reading instead of the B42 semantic path,
-- or rendering gameplay results outside the action descriptor allowlist.
remotePlayer.inventory = "unchanged"
local actionRendered, actionRenderReason = RemoteRenderer.apply(api, remotePlayer, {
    kind = "action",
    key = "read",
    phase = "start",
    begin = true,
    animationVars = {
        PerformingAction = true,
        IsReading = true,
        ReadType = "book",
        DoAnything = true,
        inventory = "changed"
    },
    payload = { xp = 100, damage = 50 },
    inventory = "changed",
    recipe = "changed"
})
A.equal(actionRendered, true)
A.equal(actionRenderReason, "ok")
A.equal(remotePlayer.reading, true)
A.equal(remotePlayer.animationVars.ReadType, "book")
A.equal(remotePlayer.animationVars.PerformingAction, nil)
A.equal(remotePlayer.animationVars.IsReading, nil)
A.equal(remotePlayer.reportedEvents[1], "EventRead")
A.equal(remotePlayer.animationVars.DoAnything, nil)
A.equal(remotePlayer.animationVars.inventory, nil)
A.equal(remotePlayer.inventory, "unchanged")

-- Break caught: bypassing the normal animation value bound when relaying ReadType.
RemoteRenderer.apply(api, remotePlayer, {
    kind = "action",
    key = "read",
    phase = "start",
    animationVars = { ReadType = string.rep("x", 65) }
})
A.equal(api.readingCalls[#api.readingCalls].readType, nil)

-- Break caught: restoring any vehicle or trailer transform renderer.
local vehicleRendered, vehicleRenderReason = RemoteRenderer.apply(api, localVehicle, {
    kind = "vehicle",
    transform = transform(99, 88)
})
A.equal(vehicleRendered, nil)
A.equal(vehicleRenderReason, "native-authority")
A.equal(localVehicle.x, 10)
A.equal(localVehicle.y, 20)

-- Break caught: moving an attached trailer whose towing vehicle is controlled by the local driver.
local trailerRendered, trailerRenderReason = RemoteRenderer.apply(api, trailer, {
    kind = "trailer",
    transform = transform(70, 80)
})
A.equal(trailerRendered, nil)
A.equal(trailerRenderReason, "native-authority")
A.equal(trailer.x, 8)
A.equal(trailer.y, 20)

-- Remote trailers also remain native-authoritative.
local remoteTrailerRendered, remoteTrailerReason = RemoteRenderer.apply(api, remoteTrailer, {
    kind = "trailer",
    transform = transform(12, 13)
})
A.equal(remoteTrailerRendered, nil)
A.equal(remoteTrailerReason, "native-authority")
A.equal(remoteTrailer.x, 4)
A.equal(remoteTrailer.y, 5)

-- Break caught: an exported compatibility mapping naming a missing canonical descriptor or unregistered class.
local compatibilityAdapters = ActionRegistry.compatibilityAdapters()
A.equal(#compatibilityAdapters, 1)
for index = 1, #compatibilityAdapters do
    local entry = compatibilityAdapters[index]
    local canonical = ActionRegistry.get(entry.actionKey)
    A.equal(canonical ~= nil, true)
    A.equal(canonical.key, entry.actionKey)

    local activated, activationReason = ActionRegistry.activateCompatibilityAdapter(entry.modId)
    A.equal(activated, true)
    A.equal(activationReason, "ok")
    for classIndex = 1, #entry.classes do
        A.equal(ActionRegistry.resolve(entry.classes[classIndex]).key, entry.actionKey)
    end

    local descriptor, descriptorReason = StateSampler.adapterDescriptor(entry.modId)
    A.equal(descriptorReason, "ok")
    A.equal(descriptor.modId, entry.modId)
    A.equal(descriptor.workshopId, entry.workshopId)
    A.equal(descriptor.disposition, entry.disposition)
    A.equal(descriptor.actionKey, entry.actionKey)
    A.equal(descriptor.classes, nil)
    A.equal(descriptor.lua, nil)
end
local fallback, fallbackReason = StateSampler.adapterDescriptor("WayMoreCars")
A.equal(fallback, nil)
A.equal(fallbackReason, "vanilla-fallback")

-- Break caught: server state retaining a game object or executable callback instead of IDs and policy tables.
local serverState = ServerState.new()
A.equal(serverState.playerPolicyById ~= nil, true)
A.equal(serverState.actionPolicyByPlayerId ~= nil, true)
A.equal(serverState.vehiclePolicyById ~= nil, true)
A.equal(serverState.trailerPolicyById ~= nil, true)
A.equal(serverState.disabledCategories ~= nil, true)
assertPlain(serverState)

-- Break caught: a semantic reading API exception disabling player rendering or escaping the adapter boundary.
local failingApi = FakePZ.new()
local failingRemote = failingApi.addPlayer({ id = "failing-remote" })
failingApi.applyReadingState = function()
    error("fake reading failure")
end
local exceptionOk, failedResult, failedReason = pcall(RemoteRenderer.apply, failingApi, failingRemote, {
    kind = "action",
    key = "read",
    phase = "start",
    begin = true,
    animationVars = { ReadType = "book" }
})
A.equal(exceptionOk, true)
A.equal(failedResult, nil)
A.equal(failedReason, "adapter-error")
A.equal(RemoteRenderer.disabledCategories.action, "adapter-error")
A.equal(RemoteRenderer.disabledCategories.player, nil)

local otherCategoryResult, otherCategoryReason = RemoteRenderer.apply(failingApi, failingRemote, {
    kind = "player",
    pose = { x = 2, y = 3, z = 0, facing = 10 }
})
A.equal(otherCategoryResult, nil)
A.equal(otherCategoryReason, "native-authority")
A.equal(failingRemote.x, 0)
A.equal(failingRemote.y, 0)

print("PASS adapters")
