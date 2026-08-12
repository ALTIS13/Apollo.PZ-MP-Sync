local FakePZ = {}

local function copyTransform(source)
    return {
        x = source.x,
        y = source.y,
        z = source.z,
        angle = source.angle,
        vx = source.vx,
        vy = source.vy,
        angularVelocity = source.angularVelocity,
        moving = source.moving
    }
end

function FakePZ.new()
    local api = {
        events = {
            OnTick = {},
            OnPlayerUpdate = {},
            OnServerCommand = {},
            OnClientCommand = {}
        },
        clock = { nowMs = 1000 },
        players = {},
        vehicles = {},
        commands = {},
        animationCalls = {},
        readingCalls = {},
        poseCalls = {},
        vehicleTransformCalls = {},
        activeMods = { SleepWithFriends = true },
        nextOnlineId = 1,
        nativeBridge = nil,
        nativeStateLogs = {},
        diagnostics = {}
    }

    function api.installNativeBridge(spec)
        spec = spec or {}
        local bridge = {
            state = spec.state or "ABSENT",
            reasonCode = spec.reasonCode or "test-state",
            bridgeProtocol = spec.bridgeProtocol or "1",
            fingerprintSha256 = spec.fingerprintSha256 or "test-fingerprint",
            handshakeState = spec.handshakeState,
            handshakeReasonCode = spec.handshakeReasonCode,
            metrics = spec.metrics or {},
            statusCalls = 0,
            handshakeCalls = 0,
            metricsCalls = 0,
            handshakes = {}
        }
        api.nativeBridge = bridge
        return bridge
    end

    function api.getNativeBridge()
        return api.nativeBridge
    end

    function api.nativeBridgeStatus(bridge)
        bridge.statusCalls = bridge.statusCalls + 1
        return {
            state = bridge.state,
            reasonCode = bridge.reasonCode,
            bridgeProtocol = bridge.bridgeProtocol,
            fingerprintSha256 = bridge.fingerprintSha256
        }
    end

    function api.nativeBridgeHandshake(bridge, handshake)
        bridge.handshakeCalls = bridge.handshakeCalls + 1
        bridge.handshakes[#bridge.handshakes + 1] = handshake
        if handshake.nativeAssistEnabled == false then
            bridge.state = "DISABLED"
            bridge.reasonCode = "config-disabled"
            bridge.deauthorizedByHandshake = true
        else
            if bridge.deauthorizedByHandshake then
                bridge.state = bridge.handshakeState or "READY"
                bridge.reasonCode = bridge.handshakeReasonCode or "hooks-armed"
                bridge.deauthorizedByHandshake = false
            else
                bridge.state = bridge.handshakeState or bridge.state
                bridge.reasonCode = bridge.handshakeReasonCode or bridge.reasonCode
            end
        end
        return api.nativeBridgeStatus(bridge)
    end

    function api.nativeBridgeMetrics(bridge)
        bridge.metricsCalls = bridge.metricsCalls + 1
        local result = {}
        for key, value in pairs(bridge.metrics) do result[key] = value end
        return result
    end

    function api.logNativeAssistState(status)
        api.nativeStateLogs[#api.nativeStateLogs + 1] = status
    end

    function api.logDiagnostics(summary)
        api.diagnostics[#api.diagnostics + 1] = summary
    end

    function api.addPlayer(spec)
        local onlineId = spec.onlineId or api.nextOnlineId
        api.nextOnlineId = math.max(api.nextOnlineId, onlineId + 1)
        local player = {
            kind = "fake-pz-player",
            id = spec.id,
            username = spec.username or ("user-" .. tostring(spec.id)),
            steamId = spec.steamId or ("steam-" .. tostring(spec.id)),
            onlineId = onlineId,
            x = spec.x or 0,
            y = spec.y or 0,
            z = spec.z or 0,
            facing = spec.facing or 0,
            pingMs = spec.pingMs or 0,
            localPlayer = spec.localPlayer == true,
            currentAction = spec.currentAction,
            vehicleId = spec.vehicleId,
            animationVars = spec.animationVars or {},
            reading = spec.reading == true,
            reportedEvents = {}
        }
        api.players[player.id] = player
        return player
    end

    function api.addVehicle(spec)
        local vehicle = {
            kind = "fake-pz-vehicle",
            id = spec.id,
            driverId = spec.driverId,
            x = spec.x or 0,
            y = spec.y or 0,
            z = spec.z or 0,
            angle = spec.angle or 0,
            vx = spec.vx or 0,
            vy = spec.vy or 0,
            angularVelocity = spec.angularVelocity or 0,
            moving = spec.moving == true,
            trailerId = spec.trailerId,
            towingVehicleId = spec.towingVehicleId
        }
        api.vehicles[vehicle.id] = vehicle
        return vehicle
    end

    function api.nowMs()
        return api.clock.nowMs
    end

    function api.getPlayerId(player)
        return player.id
    end

    function api.getPlayerOnlineId(player)
        return player.onlineId
    end

    function api.getStablePlayerId(player)
        return player.steamId
    end

    function api.getPlayerPose(player)
        return { x = player.x, y = player.y, z = player.z, facing = player.facing }
    end

    function api.getPlayerPingMs(player)
        return player.pingMs
    end

    function api.getCurrentAction(player)
        return player.currentAction
    end

    function api.getActionClassName(action)
        return action.className
    end

    function api.getActionPhase(action)
        return action.phase
    end

    function api.getActionProgress(action)
        return action.progress
    end

    function api.getActionSequence(action)
        return action.sequence
    end

    function api.getAnimationVariable(player, name)
        if player.currentAction and player.currentAction.animationVars[name] ~= nil then
            return player.currentAction.animationVars[name]
        end
        return player.animationVars[name]
    end

    function api.getVehicleForPlayer(player)
        return player.vehicleId and api.vehicles[player.vehicleId] or nil
    end

    function api.getVehicleId(vehicle)
        return vehicle.id
    end

    function api.getVehicleDriverId(vehicle)
        return vehicle.driverId
    end

    function api.getVehicleTransform(vehicle)
        return copyTransform(vehicle)
    end

    function api.getTrailerForVehicle(vehicle)
        return vehicle.trailerId and api.vehicles[vehicle.trailerId] or nil
    end

    function api.getVehicleTowedBy(trailer)
        return trailer.towingVehicleId and api.vehicles[trailer.towingVehicleId] or nil
    end

    function api.getTowingVehicleId(trailer)
        return trailer.towingVehicleId
    end

    function api.isLocalPlayer(player)
        return player.localPlayer == true
    end

    function api.isLocalDriver(vehicle)
        local driver = vehicle.driverId and api.players[vehicle.driverId] or nil
        return driver ~= nil and driver.localPlayer == true
    end

    function api.isLocalTrailerAuthority(trailer)
        local towingVehicle = trailer.towingVehicleId and api.vehicles[trailer.towingVehicleId] or nil
        local driver = towingVehicle and towingVehicle.driverId and api.players[towingVehicle.driverId] or nil
        return driver ~= nil and driver.localPlayer == true
    end

    function api.applyPlayerPose(player, pose)
        player.x = pose.x
        player.y = pose.y
        player.z = pose.z
        player.facing = pose.facing
        api.poseCalls[#api.poseCalls + 1] = {
            playerId = player.id,
            pose = { x = pose.x, y = pose.y, z = pose.z, facing = pose.facing }
        }
    end

    function api.applyAnimationVariable(player, name, value)
        player.animationVars[name] = value
        api.animationCalls[#api.animationCalls + 1] = {
            playerId = player.id,
            name = name,
            value = value
        }
    end

    function api.applyReadingState(player, active, begin, readType)
        player.reading = active == true
        if active and type(readType) == "string" then
            player.animationVars.ReadType = readType
        elseif not active then
            player.animationVars.ReadType = nil
        end
        if active and begin then
            player.reportedEvents[#player.reportedEvents + 1] = "EventRead"
        end
        api.readingCalls[#api.readingCalls + 1] = {
            playerId = player.id,
            active = active == true,
            begin = begin == true,
            readType = readType
        }
    end

    function api.applyVehicleTransform(vehicle, transform)
        vehicle.x = transform.x
        vehicle.y = transform.y
        vehicle.z = transform.z
        vehicle.angle = transform.angle
        vehicle.vx = transform.vx
        vehicle.vy = transform.vy
        vehicle.angularVelocity = transform.angularVelocity
        vehicle.moving = transform.moving
        api.vehicleTransformCalls[#api.vehicleTransformCalls + 1] = {
            vehicleId = vehicle.id,
            transform = copyTransform(transform)
        }
    end

    function api.sendCommand(moduleName, commandName, args)
        api.commands[#api.commands + 1] = {
            module = moduleName,
            command = commandName,
            args = args
        }
    end

    function api.getObservedPlayer(player)
        return { id = player.steamId, x = player.x, y = player.y, z = player.z }
    end

    function api.getOnlinePlayers()
        local result = {}
        for _, player in pairs(api.players) do result[#result + 1] = player end
        return result
    end

    function api.sendServerCommand(player, moduleName, commandName, args)
        api.serverCommands = api.serverCommands or {}
        api.serverCommands[#api.serverCommands + 1] = {
            player = player,
            module = moduleName,
            command = commandName,
            args = args
        }
    end

    return api
end

return FakePZ
