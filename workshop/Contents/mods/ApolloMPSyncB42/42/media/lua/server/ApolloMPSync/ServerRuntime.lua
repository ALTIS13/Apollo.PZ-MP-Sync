local ActionPolicy = require("ApolloMPSync/ActionPolicy")
local ActionRegistry = require("ApolloMPSync/ActionRegistry")
local Config = require("ApolloMPSync/Config")
local NativeAssistServer = require("ApolloMPSync/NativeAssistServer")
local Protocol = require("ApolloMPSync/Protocol")
local RateLimit = require("ApolloMPSync/RateLimit")
local Sequence = require("ApolloMPSync/Sequence")
local ServerState = require("ApolloMPSync/ServerState")
local TrailerPolicy = require("ApolloMPSync/TrailerPolicy")
local Validation = require("ApolloMPSync/Validation")
local VehiclePolicy = require("ApolloMPSync/VehiclePolicy")
local VehicleRepair = require("ApolloMPSync/VehicleRepair")

local ServerRuntime = {}
ServerRuntime.__index = ServerRuntime

local MAX_OBJECT_ID = 2147483647
local OBJECT_TTL_MS = 120000
local PRUNE_INTERVAL_MS = 60000
local DIAGNOSTICS_INTERVAL_MS = 60000

local function finite(value)
    return type(value) == "number"
        and value == value
        and value ~= math.huge
        and value ~= -math.huge
end

local function increment(map, key)
    map[key] = (map[key] or 0) + 1
end

local function positiveId(value)
    return finite(value) and value >= 1 and value <= MAX_OBJECT_ID and value == math.floor(value)
end

local function validOnlineId(value)
    return finite(value) and value >= 0 and value <= MAX_OBJECT_ID and value == math.floor(value)
end

local function clamp(value, lower, upper)
    if value < lower then return lower end
    if value > upper then return upper end
    return value
end

local function mapText(values)
    local entries = {}
    for key, value in pairs(values or {}) do
        entries[#entries + 1] = tostring(key) .. ":" .. tostring(value)
    end
    table.sort(entries)
    return #entries > 0 and table.concat(entries, ",") or "none"
end

function ServerRuntime.formatDiagnostics(summary)
    summary = type(summary) == "table" and summary or {}
    local native = type(summary.nativeAssist) == "table" and summary.nativeAssist or {}
    return "accepted=" .. mapText(summary.accepted)
        .. " rejected=" .. mapText(summary.rejected)
        .. " corrections=" .. tostring(summary.corrections or 0)
        .. " fallbacks=" .. tostring(summary.fallbacks or 0)
        .. " disabled=" .. mapText(summary.disabledAdapters)
        .. " native=" .. tostring(native.state or "ABSENT")
        .. " nativeAccepted=" .. mapText(native.accepted)
        .. " nativeRejected=" .. mapText(native.rejected)
        .. " historyMisses=" .. tostring(native.historyMisses or 0)
        .. " rttFallbacks=" .. tostring(native.rttFallbacks or 0)
        .. " jitterFallbacks=" .. tostring(native.jitterFallbacks or 0)
        .. " bubble=" .. mapText(native.bubblePopulation)
        .. " handoffs=" .. tostring(native.handoffs or 0)
        .. " circuit=" .. tostring(native.circuitState or 0)
end

local function copy(value)
    local result = {}
    if type(value) == "table" then
        for key, entry in pairs(value) do
            result[key] = entry
        end
    end
    return result
end

local function distanceSquared(a, b)
    local dx = a.x - b.x
    local dy = a.y - b.y
    return dx * dx + dy * dy
end

local function knownChannel(channel)
    for _, value in pairs(Protocol.CHANNELS) do
        if channel == value then
            return true
        end
    end
    return false
end

local function minimumInterval(channel)
    if channel == Protocol.CHANNELS.ping then
        return 1000
    end
    return 100
end

local function rateLimitKey(channel, payload)
    if channel == Protocol.CHANNELS.action and type(payload) == "table"
        and (payload.phase == "cancel" or payload.phase == "complete") then
        return channel .. ":terminal"
    end
    return channel
end

local function channelEnabled(config, channel)
    if config.enabled == false then return false end
    if channel == Protocol.CHANNELS.player then return config.playerSync ~= false end
    if channel == Protocol.CHANNELS.action then
        return config.actionSync ~= false and config.actionEnabled ~= false
    end
    if channel == Protocol.CHANNELS.vehicle then return config.vehicleTransformSync ~= false end
    if channel == Protocol.CHANNELS.trailer then return config.trailerSync ~= false end
    if channel == Protocol.CHANNELS.parts then return config.vehicleVisualPartSync ~= false end
    return channel == Protocol.CHANNELS.ping
end

local function validTransform(value)
    return type(value) == "table"
        and finite(value.x) and finite(value.y) and finite(value.z)
        and finite(value.angle) and finite(value.vx) and finite(value.vy)
        and finite(value.angularVelocity) and type(value.moving) == "boolean"
end

function ServerRuntime.new(api, config)
    config = type(config) == "table" and config or Config.defaults()
    local now = type(api) == "table" and type(api.nowMs) == "function" and api.nowMs() or 0
    local state = ServerState.new()
    state.sequenceByPlayerId = {}
    state.objectSequences = {}
    state.vehicleAuthorityById = {}
    state.trailerAuthorityById = {}
    state.trailerRelationById = {}
    state.trailerRelationLastSeenById = {}
    state.actorLifecycleByStableId = {}
    state.objectLastSeen = {}
    state.disabledCategories = state.disabledCategories or {}
    local runtime = setmetatable({
        api = api,
        config = config,
        state = state,
        repair = VehicleRepair.new(api, 4),
        diagnostics = {
            accepted = {},
            rejected = {},
            corrections = 0,
            fallbacks = 0,
            disabledAdapters = {}
        },
        lastRepairMs = now,
        lastDiagnosticsMs = now,
        nextPruneMs = now + PRUNE_INTERVAL_MS
    }, ServerRuntime)
    runtime.nativeAssist = NativeAssistServer.new(api, config):initialize()
    return runtime
end

function ServerRuntime:reject(reason)
    increment(self.diagnostics.rejected, reason or "unknown")
end

function ServerRuntime:categoryCall(category, operation)
    if self.state.disabledCategories[category] ~= nil then
        self:reject("adapter-disabled")
        return nil, "adapter-disabled"
    end
    local ok, result, reason = pcall(operation)
    if not ok then
        self.state.disabledCategories[category] = "adapter-error"
        self.diagnostics.disabledAdapters[category] = "adapter-error"
        self:reject("adapter-error")
        return nil, "adapter-error"
    end
    return result, reason
end

function ServerRuntime:clearActor(senderId)
    self.state.rateLimitByPlayerId[senderId] = nil
    self.state.sequenceByPlayerId[senderId] = nil
    self.state.playerPolicyById[senderId] = nil
    self.state.actionPolicyByPlayerId[senderId] = nil
end

function ServerRuntime:checkEnvelope(senderId, actorOnlineId, channel, payload, nowMs)
    if type(payload) ~= "table" then
        return false, "payload"
    end
    if not Protocol.isCompatible(payload) then
        return false, "protocol-version"
    end
    local epoch = payload.epoch
    if type(epoch) ~= "string" or epoch == "" then
        return false, "invalid-epoch"
    end

    local lifecycle = self.state.actorLifecycleByStableId[senderId]
    if lifecycle ~= nil and lifecycle.onlineId == actorOnlineId and lifecycle.epoch ~= epoch then
        return false, "epoch"
    end
    if lifecycle == nil or lifecycle.onlineId ~= actorOnlineId then
        self:clearActor(senderId)
        lifecycle = { onlineId = actorOnlineId, epoch = epoch, lastSeenMs = nowMs }
        self.state.actorLifecycleByStableId[senderId] = lifecycle
    else
        lifecycle.lastSeenMs = nowMs
    end

    local rateState = self.state.rateLimitByPlayerId[senderId]
    if rateState == nil then
        rateState = {}
        self.state.rateLimitByPlayerId[senderId] = rateState
    end
    local rateAccepted, rateReason = RateLimit.accept(rateState,
        rateLimitKey(channel, payload), nowMs, minimumInterval(channel))
    if not rateAccepted then
        return false, rateReason
    end

    local playerSequences = self.state.sequenceByPlayerId[senderId]
    if playerSequences == nil then
        playerSequences = {}
        self.state.sequenceByPlayerId[senderId] = playerSequences
    end
    local sequenceState = playerSequences[channel]
    if sequenceState == nil then
        sequenceState = Sequence.new()
        playerSequences[channel] = sequenceState
    end
    return Sequence.accept(sequenceState, epoch, payload.sequence)
end

function ServerRuntime:recipients(sender, origin, radius)
    if type(self.api.getOnlinePlayers) ~= "function" or type(self.api.getObservedPlayer) ~= "function" then
        return {}
    end
    local result = {}
    local radiusSquared = radius * radius
    for _, player in ipairs(self.api.getOnlinePlayers()) do
        if player ~= sender then
            local observed = self.api.getObservedPlayer(player)
            if type(observed) == "table" and finite(observed.x) and finite(observed.y)
                and distanceSquared(observed, origin) <= radiusSquared then
                result[#result + 1] = player
            end
        end
    end
    return result
end

function ServerRuntime:relay(channel, sender, origin, radius, payload)
    if type(self.api.sendServerCommand) ~= "function" then
        return false, "api-unavailable"
    end
    for _, recipient in ipairs(self:recipients(sender, origin, radius)) do
        self.api.sendServerCommand(recipient, Protocol.NAMESPACE, channel, payload)
    end
    increment(self.diagnostics.accepted, channel)
    return true, "ok"
end

function ServerRuntime:nextObjectSequence(channel, objectId)
    local key = channel .. ":" .. tostring(objectId)
    local nextValue = (self.state.objectSequences[key] or 0) + 1
    self.state.objectSequences[key] = nextValue
    self.state.objectLastSeen[key] = self.api.nowMs()
    return nextValue
end

function ServerRuntime:authorityFor(vehicleId, driverId, nowMs)
    local authority = self.state.vehicleAuthorityById[vehicleId]
    local authorityChanged = authority == nil or authority.driverId ~= driverId
    if authorityChanged then
        local epoch = authority and authority.epoch + 1 or 1
        authority = { driverId = driverId, epoch = epoch, changedAtMs = nowMs }
        self.state.vehicleAuthorityById[vehicleId] = authority
    end
    local policy = self.state.vehiclePolicyById[vehicleId]
    if policy == nil then
        policy = {}
        self.state.vehiclePolicyById[vehicleId] = policy
        VehiclePolicy.acceptAuthority(policy, tostring(vehicleId), driverId, authority.epoch, nowMs)
    elseif authorityChanged then
        VehiclePolicy.acceptAuthority(policy, tostring(vehicleId), driverId, authority.epoch, nowMs)
    end
    authority.lastSeenMs = nowMs
    return authority
end

function ServerRuntime:trailerAuthorityFor(trailerId, towingVehicleId, driverOnlineId, nowMs)
    local authority = self.state.trailerAuthorityById[trailerId]
    if authority == nil or authority.towingVehicleId ~= towingVehicleId
        or authority.driverOnlineId ~= driverOnlineId then
        authority = {
            towingVehicleId = towingVehicleId,
            driverOnlineId = driverOnlineId,
            epoch = authority and authority.epoch + 1 or 1,
            changedAtMs = nowMs
        }
        self.state.trailerAuthorityById[trailerId] = authority
    end
    authority.lastSeenMs = nowMs
    return authority
end

function ServerRuntime:currentDriver(vehicle)
    if type(self.api.getVehicleDriver) ~= "function" then
        return nil, nil
    end
    local driver = self.api.getVehicleDriver(vehicle)
    local driverId = driver and self.api.getStablePlayerId(driver) or nil
    return driver, driverId
end

function ServerRuntime:serverObservesTrailerRelation(trailerId, towingVehicleId)
    if not positiveId(trailerId) or not positiveId(towingVehicleId)
        or type(self.api.getVehicleById) ~= "function"
        or type(self.api.getVehicleId) ~= "function"
        or type(self.api.getVehicleTowedBy) ~= "function" then
        return false
    end
    local trailer = self.api.getVehicleById(trailerId)
    if trailer == nil or self.api.getVehicleId(trailer) ~= trailerId then
        return false
    end
    local observedTowingVehicle = self.api.getVehicleTowedBy(trailer)
    if observedTowingVehicle == nil then return false end
    local observedTowingVehicleId = self.api.getVehicleId(observedTowingVehicle)
    return positiveId(observedTowingVehicleId) and observedTowingVehicleId == towingVehicleId
end

function ServerRuntime:handlePlayer(sender, senderId, actorOnlineId, payload, nowMs)
    local observed = self.api.getObservedPlayer(sender)
    if type(observed) ~= "table" then
        return nil, "observed"
    end
    observed = copy(observed)
    observed.id = senderId
    if not finite(payload.facing) or not finite(payload.latencyMs or 0) then
        return nil, "state"
    end
    if payload.action ~= nil and ActionRegistry.get(payload.action) == nil then
        return nil, "action"
    end
    local snapshot = {
        id = senderId,
        x = payload.x,
        y = payload.y,
        z = payload.z,
        facing = clamp(payload.facing, -360, 360),
        latencyMs = clamp(payload.latencyMs or 0, 0, 5000),
        action = payload.action
    }
    local valid, reason = Validation.player(snapshot, observed)
    if not valid then
        return nil, reason
    end
    local event = copy(snapshot)
    event.kind = "player"
    event.id = nil
    event.actorOnlineId = actorOnlineId
    event.epoch = payload.epoch
    event.sequence = payload.sequence
    return self:relay(Protocol.CHANNELS.player, sender, observed,
        self.config.playerRadius or 60, event)
end

function ServerRuntime:handleAction(sender, senderId, actorOnlineId, payload, nowMs)
    local descriptor = ActionRegistry.get(payload.key)
    if descriptor == nil then
        return nil, "action"
    end
    local anchor = payload.anchor
    local observed = self.api.getObservedPlayer(sender)
    if type(anchor) ~= "table" or type(observed) ~= "table" then
        return nil, "anchor"
    end
    observed = copy(observed)
    observed.id = senderId
    local anchorValid, anchorReason = Validation.player({
        id = senderId, x = anchor.x, y = anchor.y, z = anchor.z
    }, observed)
    if not anchorValid then
        return nil, anchorReason
    end
    local policyState = self.state.actionPolicyByPlayerId[senderId]
    if policyState == nil then
        policyState = {}
        self.state.actionPolicyByPlayerId[senderId] = policyState
    end
    local className = descriptor.classes[1]
    if className == nil then
        className = "ApolloGenericTimedAction"
    end
    local event, reason = ActionPolicy.accept(policyState, {
        className = className,
        phase = payload.phase,
        progress = payload.progress,
        anchor = anchor,
        animationVars = payload.animationVars,
        sequence = payload.sequence
    }, nowMs, self.config)
    if event == nil then
        return nil, reason
    end
    event.kind = "action"
    event.actorOnlineId = actorOnlineId
    event.epoch = payload.epoch
    event.sequence = payload.sequence
    return self:relay(Protocol.CHANNELS.action, sender, observed,
        self.config.playerRadius or 60, event)
end

local function plausibleVehicle(observed, reported)
    if type(observed) ~= "table" or type(reported) ~= "table" then
        return false
    end
    observed = VehiclePolicy.sanitizeTransform(observed)
    reported = VehiclePolicy.sanitizeTransform(reported)
    return distanceSquared(observed, reported) <= 625
end

function ServerRuntime:handleVehicle(sender, senderId, actorOnlineId, payload, nowMs)
    if not positiveId(payload.vehicleId) then
        return nil, "identity"
    end
    if not validTransform(payload.transform) then return nil, "transform" end
    local vehicle = self.api.getVehicleById(payload.vehicleId)
    if vehicle == nil then
        return nil, "vehicle"
    end
    local _, driverId = self:currentDriver(vehicle)
    if driverId == nil or driverId ~= senderId then
        return nil, "authority"
    end
    local driver = self.api.getVehicleDriver(vehicle)
    local driverOnlineId = self.api.getPlayerOnlineId(driver)
    if not validOnlineId(driverOnlineId) then return nil, "identity" end
    local observed = self.api.getObservedVehicle(vehicle)
    if not plausibleVehicle(observed, payload.transform) then
        return nil, "jump"
    end
    local authority = self:authorityFor(payload.vehicleId, driverId, nowMs)
    local sequence = self:nextObjectSequence(Protocol.CHANNELS.vehicle, payload.vehicleId)
    local event = {
        kind = "vehicle",
        vehicleId = payload.vehicleId,
        driverOnlineId = driverOnlineId,
        epoch = authority.epoch,
        sequence = sequence,
        transform = VehiclePolicy.sanitizeTransform(payload.transform)
    }
    return self:relay(Protocol.CHANNELS.vehicle, sender, VehiclePolicy.sanitizeTransform(observed),
        self.config.vehicleRadius or self.config.highwayRadius or 300, event)
end

function ServerRuntime:handleTrailer(sender, senderId, actorOnlineId, payload, nowMs)
    if not positiveId(payload.towingVehicleId) or not positiveId(payload.trailerId)
        or (payload.operation ~= "attach" and payload.operation ~= "detach") then
        return nil, "trailer"
    end
    if not validTransform(payload.transform) then return nil, "transform" end
    local towingVehicle = self.api.getVehicleById(payload.towingVehicleId)
    local trailer = self.api.getVehicleById(payload.trailerId)
    if towingVehicle == nil or trailer == nil then
        return nil, "vehicle"
    end
    if type(self.api.getVehicleTowedBy) ~= "function" or type(self.api.getVehicleId) ~= "function" then
        error("inverse tow API unavailable")
    end
    local observedTowingVehicle = self.api.getVehicleTowedBy(trailer)
    if payload.operation == "attach" then
        if observedTowingVehicle == nil
            or self.api.getVehicleId(observedTowingVehicle) ~= payload.towingVehicleId then
            return nil, "relation"
        end
    else
        if self.state.trailerRelationById[payload.trailerId] ~= payload.towingVehicleId
            or observedTowingVehicle ~= nil then
            return nil, "relation"
        end
    end
    local _, authorityDriverId = self:currentDriver(towingVehicle)
    if authorityDriverId == nil or authorityDriverId ~= senderId then
        return nil, "authority"
    end
    local driver = self.api.getVehicleDriver(towingVehicle)
    local driverOnlineId = self.api.getPlayerOnlineId(driver)
    if not validOnlineId(driverOnlineId) then return nil, "identity" end
    local observedTrailer = self.api.getObservedVehicle(trailer)
    if not plausibleVehicle(observedTrailer, payload.transform) then return nil, "jump" end
    local authority = self:trailerAuthorityFor(payload.trailerId, payload.towingVehicleId,
        driverOnlineId, nowMs)
    local sequence = self:nextObjectSequence(Protocol.CHANNELS.trailer, payload.trailerId)
    local policyState = self.state.trailerPolicyById.trailers
    if policyState == nil then
        policyState = {}
        self.state.trailerPolicyById.trailers = policyState
    end
    local _, reason = TrailerPolicy.apply(policyState, {
        kind = payload.operation,
        towingVehicleId = tostring(payload.towingVehicleId),
        trailerId = tostring(payload.trailerId),
        transform = payload.transform,
        epoch = authority.epoch,
        sequence = sequence,
        senderDriverId = senderId,
        authorityDriverId = authorityDriverId
    })
    if reason ~= "ok" then
        return nil, reason
    end
    self.state.trailerRelationById[payload.trailerId] = payload.operation == "attach"
        and payload.towingVehicleId or nil
    self.state.trailerRelationLastSeenById[payload.trailerId] = nowMs
    local transform = VehiclePolicy.sanitizeTransform(payload.transform)
    local event = {
        kind = "trailer",
        operation = payload.operation,
        towingVehicleId = payload.towingVehicleId,
        trailerId = payload.trailerId,
        driverOnlineId = driverOnlineId,
        epoch = authority.epoch,
        sequence = sequence,
        transform = transform
    }
    return self:relay(Protocol.CHANNELS.trailer, sender, VehiclePolicy.sanitizeTransform(observedTrailer),
        self.config.vehicleRadius or self.config.highwayRadius or 300, event)
end

function ServerRuntime:handleParts(sender, senderId, actorOnlineId, payload, nowMs)
    if self.config.vehicleVisualPartSync == false then
        return nil, "disabled"
    end
    if not positiveId(payload.vehicleId) or type(payload.parts) ~= "table" then
        return nil, "part"
    end
    local partCount = 0
    for key in pairs(payload.parts) do
        if type(key) ~= "number" or key < 1 or key > 3 or key ~= math.floor(key) then
            return nil, "part"
        end
        partCount = partCount + 1
    end
    if partCount < 1 or partCount > 3 then return nil, "part" end
    local parts = {}
    local seen = {}
    for index = 1, partCount do
        local part = payload.parts[index]
        if type(part) ~= "table" or seen[part.partId]
            or not VehiclePolicy.isVisualPartAllowed(part.partId)
            or not finite(part.condition) or part.condition < 0 or part.condition > 100
            or type(part.open) ~= "boolean"
            or (part.locked ~= nil and type(part.locked) ~= "boolean") then
            return nil, "part"
        end
        seen[part.partId] = true
        parts[index] = {
            partId = part.partId,
            condition = math.floor(part.condition + 0.5),
            open = part.open,
            locked = part.locked
        }
    end
    local vehicle = self.api.getVehicleById(payload.vehicleId)
    if vehicle == nil then
        return nil, "vehicle"
    end
    local _, driverId = self:currentDriver(vehicle)
    if driverId == nil or driverId ~= senderId then
        return nil, "authority"
    end
    local driver = self.api.getVehicleDriver(vehicle)
    local driverOnlineId = self.api.getPlayerOnlineId(driver)
    if not validOnlineId(driverOnlineId) then return nil, "identity" end
    local observed = self.api.getObservedVehicle(vehicle)
    if type(observed) ~= "table" or not finite(observed.x)
        or not finite(observed.y) or not finite(observed.z) then
        return nil, "observed"
    end
    for _, part in ipairs(parts) do
        local queued, queueReason = self.repair:enqueue(payload.vehicleId, part.partId, part.condition)
        if not queued then return nil, queueReason end
    end
    observed = VehiclePolicy.sanitizeTransform(observed)
    local event = {
        kind = "parts",
        vehicleId = payload.vehicleId,
        parts = parts,
        driverOnlineId = driverOnlineId,
        epoch = self:authorityFor(payload.vehicleId, driverId, nowMs).epoch,
        sequence = self:nextObjectSequence(Protocol.CHANNELS.parts, payload.vehicleId)
    }
    return self:relay(Protocol.CHANNELS.parts, sender, observed,
        self.config.vehicleRadius or self.config.highwayRadius or 300, event)
end

function ServerRuntime:onClientCommand(moduleName, channel, sender, payload)
    if moduleName ~= Protocol.NAMESPACE or not knownChannel(channel) then
        return
    end
    if not channelEnabled(self.config, channel) then
        self:reject("disabled")
        return
    end
    if sender == nil or type(self.api.getStablePlayerId) ~= "function" then
        self:reject("sender")
        return
    end
    local senderId = self.api.getStablePlayerId(sender)
    if type(senderId) ~= "string" or senderId == "" then
        self:reject("sender")
        return
    end
    if type(self.api.getPlayerOnlineId) ~= "function" then
        self.state.disabledCategories.player = "api-unavailable"
        self.diagnostics.disabledAdapters.player = "api-unavailable"
        self:reject("api-unavailable")
        return
    end
    local actorOnlineId = self.api.getPlayerOnlineId(sender)
    if not validOnlineId(actorOnlineId) then self:reject("sender") return end
    local nowMs = self.api.nowMs()
    local envelopeAccepted, envelopeReason = self:checkEnvelope(senderId, actorOnlineId, channel, payload, nowMs)
    if not envelopeAccepted then
        self:reject(envelopeReason)
        return
    end
    local result, reason = self:categoryCall(channel, function()
        if channel == Protocol.CHANNELS.player then
            return self:handlePlayer(sender, senderId, actorOnlineId, payload, nowMs)
        elseif channel == Protocol.CHANNELS.action then
            return self:handleAction(sender, senderId, actorOnlineId, payload, nowMs)
        elseif channel == Protocol.CHANNELS.vehicle then
            return self:handleVehicle(sender, senderId, actorOnlineId, payload, nowMs)
        elseif channel == Protocol.CHANNELS.trailer then
            return self:handleTrailer(sender, senderId, actorOnlineId, payload, nowMs)
        elseif channel == Protocol.CHANNELS.parts then
            return self:handleParts(sender, senderId, actorOnlineId, payload, nowMs)
        end
        return nil, "channel"
    end)
    if not result and reason ~= "adapter-error" and reason ~= "adapter-disabled" then
        self:reject(reason)
    end
end

function ServerRuntime:flushDiagnostics(nowMs)
    if nowMs - self.lastDiagnosticsMs < DIAGNOSTICS_INTERVAL_MS then
        return
    end
    for category, reason in pairs(self.state.disabledCategories) do
        self.diagnostics.disabledAdapters[category] = reason
    end
    self.diagnostics.fallbacks = self.diagnostics.fallbacks + self.repair.fallbacks
    self.repair.fallbacks = 0
    self.diagnostics.nativeAssist = self.nativeAssist:collectDiagnostics()
    if type(self.api.logDiagnostics) == "function" then
        pcall(self.api.logDiagnostics, self.diagnostics)
    end
    self.lastDiagnosticsMs = nowMs
end

function ServerRuntime:prune(nowMs)
    if type(self.api.getOnlinePlayers) == "function" and type(self.api.getPlayerOnlineId) == "function" then
        local online = {}
        for _, player in ipairs(self.api.getOnlinePlayers()) do
            local onlineId = self.api.getPlayerOnlineId(player)
            if validOnlineId(onlineId) then online[onlineId] = true end
        end
        for stableId, lifecycle in pairs(self.state.actorLifecycleByStableId) do
            if not online[lifecycle.onlineId] then
                self:clearActor(stableId)
                self.state.actorLifecycleByStableId[stableId] = nil
            end
        end
    end

    for vehicleId, authority in pairs(self.state.vehicleAuthorityById) do
        if nowMs - (authority.lastSeenMs or authority.changedAtMs or 0) > OBJECT_TTL_MS then
            self.state.vehiclePolicyById[vehicleId] = nil
        end
    end
    local trailerState = self.state.trailerPolicyById.trailers
    for trailerId, authority in pairs(self.state.trailerAuthorityById) do
        if nowMs - (authority.lastSeenMs or authority.changedAtMs or 0) > OBJECT_TTL_MS then
            if trailerState then
                local key = tostring(trailerId)
                if trailerState.trailers then trailerState.trailers[key] = nil end
                if trailerState.lastTrailerSequences then trailerState.lastTrailerSequences[key] = nil end
                for towingId, chain in pairs(trailerState.towChains or {}) do
                    for index = #(chain.trailers or {}), 1, -1 do
                        if chain.trailers[index] == key then table.remove(chain.trailers, index) end
                    end
                    if #(chain.trailers or {}) == 0 then trailerState.towChains[towingId] = nil end
                end
            end
        end
    end
    for key, lastSeenMs in pairs(self.state.objectLastSeen) do
        if nowMs - lastSeenMs > OBJECT_TTL_MS then
            self.state.objectLastSeen[key] = nil
        end
    end
    for trailerId, lastSeenMs in pairs(self.state.trailerRelationLastSeenById) do
        if nowMs - lastSeenMs > OBJECT_TTL_MS then
            local towingVehicleId = self.state.trailerRelationById[trailerId]
            local relationIsCurrent = false
            if towingVehicleId ~= nil then
                relationIsCurrent = self:categoryCall(Protocol.CHANNELS.trailer, function()
                    return self:serverObservesTrailerRelation(trailerId, towingVehicleId)
                end) == true
            end
            if relationIsCurrent then
                self.state.trailerRelationLastSeenById[trailerId] = nowMs
            else
                self.state.trailerRelationLastSeenById[trailerId] = nil
                self.state.trailerRelationById[trailerId] = nil
            end
        end
    end
end

function ServerRuntime:onTick()
    local nowMs = self.api.nowMs()
    self.nativeAssist:refresh()
    local repairInterval = (self.config.vehicleRepairIntervalSeconds or 5) * 1000
    if nowMs - self.lastRepairMs >= repairInterval then
        local ok = pcall(function() self.repair:drain() end)
        if not ok then
            self.state.disabledCategories.parts = "adapter-error"
            self.diagnostics.disabledAdapters.parts = "adapter-error"
            self:reject("adapter-error")
        end
        self.lastRepairMs = nowMs
    end
    if nowMs >= self.nextPruneMs then
        self:prune(nowMs)
        self.nextPruneMs = nowMs + PRUNE_INTERVAL_MS
    end
    self:flushDiagnostics(nowMs)
end

function ServerRuntime:installEvents()
    if self.eventsInstalled then return self end
    self.eventsInstalled = true
    local function register(name, category, handler)
        if type(self.api) ~= "table" or type(self.api.addEvent) ~= "function" then
            self.diagnostics.disabledAdapters["event:" .. name] = "event-unavailable"
            self.state.disabledCategories[category] = "event-unavailable"
            return
        end
        local ok, added = pcall(self.api.addEvent, name, handler)
        if not ok or added == false then
            self.diagnostics.disabledAdapters["event:" .. name] = ok and "event-unavailable" or "event-error"
            self.state.disabledCategories[category] = ok and "event-unavailable" or "event-error"
        end
    end
    register("OnClientCommand", "network", function(moduleName, channel, sender, payload)
        self:onClientCommand(moduleName, channel, sender, payload)
    end)
    register("OnTick", "maintenance", function() self:onTick() end)
    return self
end

function ServerRuntime.install(api, config)
    if type(api) == "table" and api.__apolloMPSyncServerRuntime ~= nil then
        return api.__apolloMPSyncServerRuntime
    end
    local runtime = ServerRuntime.new(api, config):installEvents()
    if type(api) == "table" then api.__apolloMPSyncServerRuntime = runtime end
    return runtime
end

local function method(object, name, ...)
    if object == nil then return nil end
    local okIndex, fn = pcall(function() return object[name] end)
    if not okIndex or type(fn) ~= "function" then return nil end
    local ok, value = pcall(fn, object, ...)
    return ok and value or nil
end

local function invoke(object, name, ...)
    if object == nil then return false end
    local okIndex, fn = pcall(function() return object[name] end)
    if not okIndex or type(fn) ~= "function" then return false end
    return pcall(fn, object, ...)
end

function ServerRuntime.defaultApi()
    local api = {}
    local events = rawget(_G, "Events")
    local visualPartIds = {
        hood = { "EngineDoor" },
        trunk = { "TrunkDoor", "DoorRear", "TrunkDoorOpened" },
        ["rear-door"] = { "DoorRearLeft", "DoorRearRight" }
    }
    function api.addEvent(name, handler)
        local event = type(events) == "table" and events[name] or nil
        if event == nil or type(event.Add) ~= "function" then return false end
        event.Add(handler)
        return true
    end
    function api.nowMs()
        local fn = rawget(_G, "getTimestampMs")
        return type(fn) == "function" and fn() or 0
    end
    function api.getStablePlayerId(player)
        local steamId = method(player, "getSteamID")
        if steamId ~= nil and tostring(steamId) ~= "" and tostring(steamId) ~= "0" then
            return tostring(steamId)
        end
        local username = method(player, "getUsername")
        return username ~= nil and tostring(username) ~= "" and tostring(username) or nil
    end
    function api.getPlayerOnlineId(player) return method(player, "getOnlineID") end
    function api.getObservedPlayer(player)
        return {
            id = api.getStablePlayerId(player),
            x = method(player, "getX"), y = method(player, "getY"), z = method(player, "getZ")
        }
    end
    function api.getOnlinePlayers()
        local fn = rawget(_G, "getOnlinePlayers")
        local collection = type(fn) == "function" and fn() or nil
        local players = {}
        local size = collection and method(collection, "size") or 0
        for index = 0, size - 1 do players[#players + 1] = method(collection, "get", index) end
        return players
    end
    function api.getVehicleById(id)
        local fn = rawget(_G, "getVehicleById")
        return type(fn) == "function" and fn(tonumber(id) or id) or nil
    end
    function api.getVehicleId(vehicle) return method(vehicle, "getId") end
    function api.getVehicleDriver(vehicle) return method(vehicle, "getDriver") end
    function api.getVehicleTowedBy(trailer) return method(trailer, "getVehicleTowedBy") end
    function api.getObservedVehicle(vehicle)
        return {
            x = method(vehicle, "getX"), y = method(vehicle, "getY"), z = method(vehicle, "getZ"),
            angle = 0, vx = 0, vy = 0, angularVelocity = 0,
            moving = math.abs(method(vehicle, "getCurrentSpeedKmHour") or 0) > 0.1
        }
    end
    function api.sendServerCommand(player, moduleName, channel, payload)
        local fn = rawget(_G, "sendServerCommand")
        if type(fn) ~= "function" then error("sendServerCommand unavailable") end
        fn(player, moduleName, channel, payload)
    end
    function api.applyVehiclePart(vehicle, partId, condition)
        local ids = visualPartIds[partId]
        if ids == nil then return false end
        local applied = false
        for _, vehiclePartId in ipairs(ids) do
            local part = method(vehicle, "getPartById", vehiclePartId)
            if part ~= nil and invoke(part, "setCondition", condition)
                and invoke(vehicle, "transmitPartCondition", part) then
                applied = true
            end
        end
        return applied
    end
    function api.logDiagnostics(summary)
        local fn = rawget(_G, "print")
        if type(fn) == "function" then
            fn("[ApolloMPSync] " .. ServerRuntime.formatDiagnostics(summary))
        end
    end
    return NativeAssistServer.decorateApi(api)
end

local isServerFn = rawget(_G, "isServer")
if type(isServerFn) == "function" then
    local ok, active = pcall(isServerFn)
    if ok and active then
        ServerRuntime.instance = ServerRuntime.install(ServerRuntime.defaultApi(),
            Config.fromSandbox(rawget(_G, "SandboxVars")))
    end
end

return ServerRuntime
