local ActionRegistry = require("ApolloMPSync/ActionRegistry")
local AdaptiveRate = require("ApolloMPSync/AdaptiveRate")
local Config = require("ApolloMPSync/Config")
local NativeAssistClient = require("ApolloMPSync/NativeAssistClient")
local Protocol = require("ApolloMPSync/Protocol")
local RemoteRenderer = require("ApolloMPSync/RemoteRenderer")
local Sequence = require("ApolloMPSync/Sequence")
local StateSampler = require("ApolloMPSync/StateSampler")
local VehiclePolicy = require("ApolloMPSync/VehiclePolicy")

local ClientRuntime = {}
ClientRuntime.__index = ClientRuntime

local epochCounter = 0

local function nextEpoch(api)
    epochCounter = epochCounter + 1
    local now = type(api) == "table" and type(api.nowMs) == "function" and api.nowMs() or 0
    return "client-" .. tostring(now) .. "-" .. tostring(epochCounter)
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

local function increment(map, key)
    map[key] = (map[key] or 0) + 1
end

local function mapText(values)
    local entries = {}
    for key, value in pairs(values or {}) do
        entries[#entries + 1] = tostring(key) .. ":" .. tostring(value)
    end
    table.sort(entries)
    return #entries > 0 and table.concat(entries, ",") or "none"
end

function ClientRuntime.formatDiagnostics(summary)
    summary = type(summary) == "table" and summary or {}
    return "accepted=" .. mapText(summary.accepted)
        .. " rejected=" .. mapText(summary.rejected)
        .. " corrections=" .. tostring(summary.corrections or 0)
        .. " fallbacks=" .. tostring(summary.fallbacks or 0)
        .. " disabled=" .. mapText(summary.disabledAdapters)
end

local function channelKnown(channel)
    for _, known in pairs(Protocol.CHANNELS) do
        if channel == known then
            return true
        end
    end
    return false
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

function ClientRuntime.new(api, config)
    config = type(config) == "table" and config or Config.defaults()
    local now = type(api) == "table" and type(api.nowMs) == "function" and api.nowMs() or 0
    return setmetatable({
        api = api,
        config = config,
        epoch = nextEpoch(api),
        sequences = {},
        incomingSequences = {},
        adaptiveState = {},
        disabledCategories = {},
        diagnostics = {
            accepted = {},
            rejected = {},
            corrections = 0,
            fallbacks = 0,
            disabledAdapters = {}
        },
        lastDiagnosticsMs = now,
        lastPlayerSentMs = nil,
        lastVehicleSentMs = nil,
        lastActionSentMs = nil,
        lastPlayerSnapshot = nil,
        activeActionKey = nil,
        actionGraceUntilMs = nil,
        attachedTrailer = nil,
        remoteActionByOnlineId = {}
    }, ClientRuntime)
end

function ClientRuntime:reject(reason)
    increment(self.diagnostics.rejected, reason or "unknown")
end

function ClientRuntime:categoryCall(category, operation)
    if self.disabledCategories[category] ~= nil then
        self:reject("adapter-disabled")
        return nil, "adapter-disabled"
    end
    local ok, result, reason = pcall(operation)
    if not ok then
        self.disabledCategories[category] = "adapter-error"
        self.diagnostics.disabledAdapters[category] = "adapter-error"
        self:reject("adapter-error")
        return nil, "adapter-error"
    end
    return result, reason
end

function ClientRuntime:send(channel, payload)
    if type(self.api) ~= "table" or type(self.api.sendClientCommand) ~= "function" then
        self.disabledCategories[channel] = "api-unavailable"
        self.diagnostics.disabledAdapters[channel] = "api-unavailable"
        self:reject("api-unavailable")
        return false
    end

    local sequence = (self.sequences[channel] or 0) + 1
    payload = copy(payload)
    payload.epoch = self.epoch
    payload.sequence = sequence
    local ok = pcall(self.api.sendClientCommand, Protocol.NAMESPACE, channel, payload)
    if not ok then
        self.disabledCategories[channel] = "adapter-error"
        self.diagnostics.disabledAdapters[channel] = "adapter-error"
        self:reject("adapter-error")
        return false
    end
    self.sequences[channel] = sequence
    increment(self.diagnostics.accepted, channel)
    return true
end

local function poseChanged(previous, current)
    return previous == nil
        or previous.x ~= current.x or previous.y ~= current.y or previous.z ~= current.z
        or previous.facing ~= current.facing
end

function ClientRuntime:samplePlayer(player, nowMs, force)
    if self.config.playerSync == false then
        return
    end
    local snapshot, reason = StateSampler.player(self.api, player)
    if snapshot == nil then
        if reason ~= "no-action" then
            self:reject(reason)
        end
        return
    end

    local moving = poseChanged(self.lastPlayerSnapshot, snapshot)
    local configured = moving and (self.config.movingIntervalMs or 200) or (self.config.idleIntervalMs or 1000)
    local adaptive = AdaptiveRate.update(self.adaptiveState, snapshot.latencyMs, "player")
    local interval = moving and math.max(configured, adaptive) or configured
    if force or self.lastPlayerSentMs == nil or nowMs - self.lastPlayerSentMs >= interval then
        self:send(Protocol.CHANNELS.player, snapshot)
        self.lastPlayerSentMs = nowMs
        self.lastPlayerSnapshot = snapshot
    end
end

function ClientRuntime:sampleAction(player, nowMs)
    if self.config.actionSync == false or self.config.actionEnabled == false then
        return
    end
    local snapshot, reason = StateSampler.action(self.api, player)
    if snapshot == nil then
        if reason == "no-action" and self.activeActionKey ~= nil then
            local playerSnapshot = StateSampler.player(self.api, player)
            if playerSnapshot ~= nil then
                self:send(Protocol.CHANNELS.action, {
                    playerId = playerSnapshot.id,
                    key = self.activeActionKey,
                    category = "timed-action",
                    phase = "cancel",
                    anchor = {
                        x = playerSnapshot.x,
                        y = playerSnapshot.y,
                        z = playerSnapshot.z,
                        facing = playerSnapshot.facing
                    },
                    animationVars = {},
                    timestampMs = nowMs
                })
                self.actionGraceUntilMs = nowMs + (self.config.endGraceMs or 1250)
            end
            self.activeActionKey = nil
        elseif reason ~= "no-action" then
            self:reject(reason)
        end
        return
    end

    if self.activeActionKey == nil and snapshot.phase == "progress" then
        snapshot.phase = "start"
    end
    if snapshot.phase == "start" and self.activeActionKey == snapshot.key then
        return
    end
    if snapshot.phase == "progress" and self.lastActionSentMs ~= nil
        and nowMs - self.lastActionSentMs < (self.config.progressIntervalMs or 500) then
        return
    end

    if self:send(Protocol.CHANNELS.action, snapshot) then
        self.lastActionSentMs = nowMs
        if snapshot.phase == "start" or snapshot.phase == "progress" then
            self.activeActionKey = snapshot.key
            self.actionGraceUntilMs = nil
        else
            self.activeActionKey = nil
            self.actionGraceUntilMs = nowMs + (self.config.endGraceMs or 1250)
        end
    end
end

function ClientRuntime:sampleVehicle(player, nowMs, force)
    if self.config.vehicleTransformSync == false then
        return
    end
    local snapshot, reason = StateSampler.vehicle(self.api, player)
    if snapshot == nil then
        if reason ~= "no-vehicle" then
            self:reject(reason)
        end
        return
    end
    local playerSnapshot = StateSampler.player(self.api, player)
    local ping = playerSnapshot and playerSnapshot.latencyMs or 0
    local interval = AdaptiveRate.update(self.adaptiveState, ping, "vehicle")
    interval = math.max(self.config.vehicleIntervalMinMs or 100,
        math.min(self.config.vehicleIntervalMaxMs or 250, interval))
    if not force and self.lastVehicleSentMs ~= nil and nowMs - self.lastVehicleSentMs < interval then
        return
    end

    self:send(Protocol.CHANNELS.vehicle, snapshot)
    self.lastVehicleSentMs = nowMs
    local vehicle = self.api.getVehicleForPlayer(player)
    local trailerSnapshot = self.config.trailerSync ~= false and StateSampler.trailer(self.api, vehicle) or nil
    if trailerSnapshot ~= nil then
        if self.attachedTrailer == nil or self.attachedTrailer.trailerId ~= trailerSnapshot.trailerId then
            local payload = copy(trailerSnapshot)
            payload.operation = "attach"
            self:send(Protocol.CHANNELS.trailer, payload)
        end
        self.attachedTrailer = trailerSnapshot
    elseif self.attachedTrailer ~= nil then
        local payload = copy(self.attachedTrailer)
        payload.operation = "detach"
        self:send(Protocol.CHANNELS.trailer, payload)
        self.attachedTrailer = nil
    end

    if self.config.vehicleVisualPartSync ~= false and type(self.api.getDirtyVehicleParts) == "function" then
        local ok, parts = pcall(self.api.getDirtyVehicleParts, vehicle)
        if ok and type(parts) == "table" then
            local batch = {}
            for _, part in ipairs(parts) do
                if #batch < 3 and type(part) == "table"
                    and VehiclePolicy.isVisualPartAllowed(part.partId) then
                    batch[#batch + 1] = {
                        partId = part.partId,
                        condition = part.condition,
                        open = part.open,
                        locked = part.locked
                    }
                end
            end
            if #batch > 0 then
                self:send(Protocol.CHANNELS.parts, {
                    vehicleId = snapshot.vehicleId,
                    parts = batch
                })
            end
        elseif not ok then
            self.disabledCategories.parts = "adapter-error"
            self.diagnostics.disabledAdapters.parts = "adapter-error"
        end
    end
end

function ClientRuntime:flushDiagnostics(nowMs)
    local interval = (self.config.diagnosticsIntervalSeconds or 60) * 1000
    if nowMs - self.lastDiagnosticsMs < interval then
        return
    end
    for category, reason in pairs(StateSampler.disabledCategories) do
        self.diagnostics.disabledAdapters[category] = reason
    end
    for category, reason in pairs(RemoteRenderer.disabledCategories) do
        self.diagnostics.disabledAdapters[category] = reason
    end
    if type(self.api.logDiagnostics) == "function" then
        pcall(self.api.logDiagnostics, self.diagnostics)
    end
    self.lastDiagnosticsMs = nowMs
end

function ClientRuntime:onPlayerUpdate(player, force)
    if self.config.enabled == false or player == nil then
        return
    end
    if type(self.api.isLocalPlayer) ~= "function" or not self.api.isLocalPlayer(player) then
        return
    end
    local nowMs = self.api.nowMs()
    self:categoryCall("player", function()
        self:samplePlayer(player, nowMs, force)
        return true
    end)
    self:categoryCall("action", function()
        self:sampleAction(player, nowMs)
        return true
    end)
    self:categoryCall("vehicle", function()
        self:sampleVehicle(player, nowMs, force)
        return true
    end)
    self:flushDiagnostics(nowMs)
end

function ClientRuntime:acceptIncoming(channel, payload, objectId)
    if type(payload) ~= "table" or type(payload.sequence) ~= "number"
        or payload.sequence < 0 or payload.sequence ~= math.floor(payload.sequence) then
        return false
    end
    local key = channel .. ":" .. tostring(objectId or "")
    local state = self.incomingSequences[key]
    if state == nil then
        state = Sequence.new()
        self.incomingSequences[key] = state
    end

    if channel == Protocol.CHANNELS.vehicle or channel == Protocol.CHANNELS.trailer then
        if state.seq ~= nil and payload.sequence <= state.seq then
            return false
        end
        state.seq = payload.sequence
        return true
    end

    local epoch = type(payload.epoch) == "string" and payload.epoch or "server"
    return Sequence.accept(state, epoch, payload.sequence)
end

function ClientRuntime:observePlayerHint(payload)
    local status = NativeAssistClient.status(self.api)
    if not status.available then self.diagnostics.fallbacks = self.diagnostics.fallbacks + 1 end
    return true, status.available and "native-assist" or "vanilla-fallback"
end

function ClientRuntime:renderAction(payload)
    if type(self.api.getPlayerByOnlineId) ~= "function" then return nil, "api-unavailable" end
    local remote = self.api.getPlayerByOnlineId(payload.actorOnlineId)
    if remote == nil then return nil, "missing" end
    local descriptor = ActionRegistry.get(payload.key)
    if descriptor == nil then return nil, "action" end

    if payload.phase == "complete" or payload.phase == "cancel" then
        local cleared = {}
        for _, name in ipairs(descriptor.animationVars) do cleared[name] = false end
        local result, reason = RemoteRenderer.apply(self.api, remote, {
            kind = "action", key = payload.key, phase = payload.phase,
            begin = false, animationVars = cleared
        })
        if not result then return result, reason end
        local duration = self.config.endGraceMs or 1250
        if type(payload.graceUntilMs) == "number" and type(payload.timestampMs) == "number" then
            duration = math.max(0, payload.graceUntilMs - payload.timestampMs)
        end
        self.remoteActionByOnlineId[payload.actorOnlineId] = {
            key = payload.key,
            graceUntilMs = self.api.nowMs() + duration
        }
        return true, "ok"
    end

    local current = self.remoteActionByOnlineId[payload.actorOnlineId]
    local rendered = copy(payload)
    rendered.begin = payload.phase == "start" or current == nil or current.key ~= payload.key
    local result, reason = RemoteRenderer.apply(self.api, remote, rendered)
    if result then
        self.remoteActionByOnlineId[payload.actorOnlineId] = { key = payload.key }
    end
    return result, reason
end

function ClientRuntime:observeVehicleHint(channel, payload)
    local status = NativeAssistClient.status(self.api)
    if not status.available then self.diagnostics.fallbacks = self.diagnostics.fallbacks + 1 end
    return true, status.available and "native-assist" or "vanilla-fallback"
end

function ClientRuntime:onServerCommand(moduleName, channel, payload)
    if moduleName ~= Protocol.NAMESPACE or not channelKnown(channel) or type(payload) ~= "table" then
        return
    end
    if not Protocol.isCompatible(payload) then
        self:reject("protocol-version")
        return
    end
    if not channelEnabled(self.config, channel) then
        self:reject("disabled")
        return
    end
    local objectId = payload.actorOnlineId or payload.vehicleId or payload.trailerId
    if not self:acceptIncoming(channel, payload, objectId) then
        self:reject("stale")
        return
    end
    self:categoryCall(channel, function()
        local result, reason
        if channel == Protocol.CHANNELS.player then
            result, reason = self:observePlayerHint(payload)
        elseif channel == Protocol.CHANNELS.action then
            result, reason = self:renderAction(payload)
        elseif channel == Protocol.CHANNELS.vehicle or channel == Protocol.CHANNELS.trailer then
            result, reason = self:observeVehicleHint(channel, payload)
        elseif channel == Protocol.CHANNELS.parts and type(self.api.applyRemoteVehiclePart) == "function" then
            local vehicle = self.api.getVehicleById(payload.vehicleId)
            if vehicle and not self.api.isLocalDriver(vehicle) and type(payload.parts) == "table" then
                result, reason = true, "ok"
                for _, part in ipairs(payload.parts) do
                    if not VehiclePolicy.isVisualPartAllowed(part.partId)
                        or self.api.applyRemoteVehiclePart(vehicle, part.partId, part.condition,
                            part.open, part.locked) == false then
                        result, reason = nil, "part"
                        break
                    end
                end
            end
        else
            result, reason = true, "vanilla-fallback"
            self.diagnostics.fallbacks = self.diagnostics.fallbacks + 1
        end
        if result then
            increment(self.diagnostics.accepted, channel)
        else
            self:reject(reason)
        end
        return result, reason
    end)
end

function ClientRuntime:cancelActiveAction(player)
    if self.activeActionKey == nil or player == nil then return false end
    local snapshot = StateSampler.player(self.api, player)
    if snapshot == nil then return false end
    local sent = self:send(Protocol.CHANNELS.action, {
        playerId = snapshot.id,
        key = self.activeActionKey,
        phase = "cancel",
        anchor = { x = snapshot.x, y = snapshot.y, z = snapshot.z, facing = snapshot.facing },
        animationVars = {},
        timestampMs = self.api.nowMs()
    })
    if sent then
        self.activeActionKey = nil
        self.actionGraceUntilMs = self.api.nowMs() + (self.config.endGraceMs or 1250)
    end
    return sent
end

function ClientRuntime:resetLocalState(rotateEpoch)
    if rotateEpoch then self.epoch = nextEpoch(self.api) end
    self.sequences = {}
    self.incomingSequences = {}
    self.adaptiveState = {}
    self.remoteActionByOnlineId = {}
    self.disabledCategories = {}
    self.diagnostics.disabledAdapters = {}
    StateSampler.disabledCategories = {}
    RemoteRenderer.disabledCategories = {}
    self.lastPlayerSentMs = nil
    self.lastVehicleSentMs = nil
    self.lastActionSentMs = nil
    self.lastPlayerSnapshot = nil
    self.activeActionKey = nil
    self.actionGraceUntilMs = nil
    self.attachedTrailer = nil
    if type(self.api) == "table" and type(self.api.resetVisualPartCache) == "function" then
        pcall(self.api.resetVisualPartCache)
    end
end

function ClientRuntime:activateObservedAdapters()
    if type(self.api.getObservedActionClasses) ~= "function" then
        return
    end
    for _, adapter in ipairs(ActionRegistry.compatibilityAdapters()) do
        local ok, observed = pcall(self.api.getObservedActionClasses, adapter.modId)
        if ok and type(observed) == "table" then
            local allowed = {}
            for _, className in ipairs(adapter.classes) do
                allowed[className] = true
            end
            local found = false
            for _, className in ipairs(observed) do
                if allowed[className] then
                    found = true
                end
            end
            if found then
                ActionRegistry.activateCompatibilityAdapter(adapter.modId)
            end
        end
    end
end

function ClientRuntime:installEvents()
    if self.eventsInstalled then return self end
    self.eventsInstalled = true
    local function register(name, category, handler)
        if type(self.api) ~= "table" or type(self.api.addEvent) ~= "function" then
            self.diagnostics.disabledAdapters["event:" .. name] = "event-unavailable"
            self.disabledCategories[category] = "event-unavailable"
            return
        end
        local ok, added = pcall(self.api.addEvent, name, handler)
        if not ok or added == false then
            local reason = ok and "event-unavailable" or "event-error"
            self.diagnostics.disabledAdapters["event:" .. name] = reason
            self.disabledCategories[category] = reason
        end
    end
    register("OnPlayerUpdate", "player", function(player) self:onPlayerUpdate(player, false) end)
    register("OnServerCommand", "receive", function(moduleName, channel, payload)
        self:onServerCommand(moduleName, channel, payload)
    end)
    register("OnPlayerDeath", "lifecycle", function(player)
        if player == nil or type(self.api.isLocalPlayer) ~= "function" or self.api.isLocalPlayer(player) then
            self:cancelActiveAction(player)
        end
    end)
    register("OnDisconnect", "lifecycle", function() self:resetLocalState(true) end)
    local vehicleHandler = function(player) self:onPlayerUpdate(player, true) end
    register("OnEnterVehicle", "vehicle", vehicleHandler)
    register("OnExitVehicle", "vehicle", vehicleHandler)
    register("OnSwitchVehicleSeat", "vehicle", vehicleHandler)
    return self
end

function ClientRuntime.install(api, config)
    if type(api) == "table" and api.__apolloMPSyncClientRuntime ~= nil then
        return api.__apolloMPSyncClientRuntime
    end
    local runtime = ClientRuntime.new(api, config)
    runtime:activateObservedAdapters()
    runtime:installEvents()
    if type(api) == "table" then api.__apolloMPSyncClientRuntime = runtime end
    return runtime
end

local function method(object, name, ...)
    if object == nil then
        return nil
    end
    local okIndex, fn = pcall(function() return object[name] end)
    if not okIndex or type(fn) ~= "function" then
        return nil
    end
    local ok, value = pcall(fn, object, ...)
    return ok and value or nil
end

function ClientRuntime.defaultApi()
    local api = {}
    local events = rawget(_G, "Events")
    api.visualPartCache = {}
    function api.addEvent(name, handler)
        local event = type(events) == "table" and events[name] or nil
        if event == nil or type(event.Add) ~= "function" then
            return false
        end
        event.Add(handler)
        return true
    end
    function api.nowMs()
        local fn = rawget(_G, "getTimestampMs")
        return type(fn) == "function" and fn() or 0
    end
    function api.sendClientCommand(moduleName, channel, payload)
        local fn = rawget(_G, "sendClientCommand")
        if type(fn) ~= "function" then error("sendClientCommand unavailable") end
        fn(moduleName, channel, payload)
    end
    function api.getPlayerId(player)
        return tostring(method(player, "getUsername") or method(player, "getOnlineID") or "")
    end
    function api.getPlayerOnlineId(player) return method(player, "getOnlineID") end
    function api.getPlayerPose(player)
        local forward = method(player, "getForwardDirection")
        return {
            x = method(player, "getX"), y = method(player, "getY"), z = method(player, "getZ"),
            facing = forward and method(forward, "getDirection") or 0
        }
    end
    function api.getPlayerPingMs() return 0 end
    function api.getCurrentAction(player)
        local queueType = rawget(_G, "ISTimedActionQueue")
        local queues = type(queueType) == "table" and queueType.queues or nil
        local queue = type(queues) == "table" and queues[player] or nil
        return queue and (queue.current or queue.queue and queue.queue[1]) or nil
    end
    function api.getActionClassName(action) return type(action) == "table" and action.Type or nil end
    function api.getActionPhase(action)
        local started = method(action, "isStarted")
        return started == false and "start" or "progress"
    end
    function api.getActionProgress(action) return method(action, "getJobDelta") end
    function api.getActionSequence() return nil end
    function api.getAnimationVariable(player, name) return method(player, "getVariableString", name) end
    function api.getVehicleForPlayer(player) return method(player, "getVehicle") end
    function api.getVehicleId(vehicle) return method(vehicle, "getId") end
    function api.getVehicleDriverId(vehicle)
        local driver = method(vehicle, "getDriver")
        return driver and api.getPlayerId(driver) or nil
    end
    function api.getVehicleTransform(vehicle)
        return {
            x = method(vehicle, "getX"), y = method(vehicle, "getY"), z = method(vehicle, "getZ"),
            angle = 0, vx = 0, vy = 0, angularVelocity = 0,
            moving = math.abs(method(vehicle, "getCurrentSpeedKmHour") or 0) > 0.1
        }
    end
    function api.getTrailerForVehicle(vehicle) return method(vehicle, "getVehicleTowing") end
    function api.getTowingVehicleId(trailer)
        local towing = method(trailer, "getVehicleTowedBy")
        return towing and api.getVehicleId(towing) or nil
    end
    function api.isLocalPlayer(player) return method(player, "isLocalPlayer") == true end
    function api.isLocalDriver(vehicle)
        local driver = method(vehicle, "getDriver")
        return driver ~= nil and api.isLocalPlayer(driver)
    end
    function api.isLocalTrailerAuthority(trailer)
        local towing = method(trailer, "getVehicleTowedBy")
        return towing ~= nil and api.isLocalDriver(towing)
    end
    function api.applyAnimationVariable(player, name, value) method(player, "setVariable", name, value) end
    function api.applyReadingState(player, active, begin, readType)
        if active then
            if type(readType) == "string" then
                method(player, "setVariable", "ReadType", readType)
            end
            method(player, "setReading", true)
            if begin then
                method(player, "reportEvent", "EventRead")
            end
        else
            method(player, "setReading", false)
            method(player, "clearVariable", "ReadType")
        end
    end
    function api.getPlayerByOnlineId(onlineId)
        local fn = rawget(_G, "getPlayerByOnlineID")
        return type(fn) == "function" and fn(onlineId) or nil
    end
    function api.getVehicleById(id)
        local fn = rawget(_G, "getVehicleById")
        return type(fn) == "function" and fn(tonumber(id) or id) or nil
    end
    function api.getLocalPlayer()
        local fn = rawget(_G, "getPlayer")
        return type(fn) == "function" and fn() or nil
    end
    local function readVisualPart(vehicle, ids)
        local condition, opened, locked, found
        for _, id in ipairs(ids) do
            local part = method(vehicle, "getPartById", id)
            if part ~= nil then
                local current = method(part, "getCondition")
                if type(current) == "number" then
                    condition = condition == nil and current or math.min(condition, current)
                    found = true
                end
                local door = method(part, "getDoor")
                if door ~= nil then
                    opened = (opened == true) or method(door, "isOpen") == true
                    locked = (locked == true) or method(door, "isLocked") == true
                end
            end
        end
        if not found then return nil end
        return { condition = condition, open = opened == true, locked = locked == true }
    end
    function api.getDirtyVehicleParts(vehicle)
        local vehicleId = api.getVehicleId(vehicle)
        if type(vehicleId) ~= "number" then return {} end
        local cache = api.visualPartCache[vehicleId] or {}
        api.visualPartCache[vehicleId] = cache
        local dirty = {}
        local definitions = {
            { "hood", { "EngineDoor" } },
            { "trunk", { "TrunkDoor", "DoorRear", "TrunkDoorOpened" } },
            { "rear-door", { "DoorRearLeft", "DoorRearRight" } }
        }
        for _, definition in ipairs(definitions) do
            local state = readVisualPart(vehicle, definition[2])
            if state ~= nil then
                local signature = tostring(state.condition) .. ":" .. tostring(state.open)
                    .. ":" .. tostring(state.locked)
                if cache[definition[1]] ~= signature then
                    cache[definition[1]] = signature
                    state.partId = definition[1]
                    dirty[#dirty + 1] = state
                end
            end
        end
        return dirty
    end
    function api.resetVisualPartCache()
        api.visualPartCache = {}
    end
    function api.getObservedActionClasses(modId)
        local classes = {}
        local modsFn = rawget(_G, "getActivatedMods")
        local mods = type(modsFn) == "function" and modsFn() or nil
        if mods == nil or method(mods, "contains", modId) ~= true then return classes end
        local adapter = ActionRegistry.compatibilityAdapter(modId)
        if adapter then
            for _, className in ipairs(adapter.classes) do
                if rawget(_G, className) ~= nil then classes[#classes + 1] = className end
            end
        end
        return classes
    end
    function api.logDiagnostics(summary)
        local fn = rawget(_G, "print")
        if type(fn) == "function" then
            fn("[ApolloMPSync] " .. ClientRuntime.formatDiagnostics(summary))
        end
    end
    return api
end

local isClientFn = rawget(_G, "isClient")
if type(isClientFn) == "function" then
    local ok, active = pcall(isClientFn)
    if ok and active then
        ClientRuntime.instance = ClientRuntime.install(ClientRuntime.defaultApi(),
            Config.fromSandbox(rawget(_G, "SandboxVars")))
    end
end

return ClientRuntime
