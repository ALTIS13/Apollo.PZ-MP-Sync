local ActionRegistry = require("ApolloMPSync/ActionRegistry")

local RemoteRenderer = {
    disabledCategories = {}
}

local function isFinite(value)
    return type(value) == "number"
        and value == value
        and value ~= math.huge
        and value ~= -math.huge
end

local function safeAnimationValue(value)
    return type(value) == "boolean"
        or isFinite(value)
        or (type(value) == "string" and #value <= 64)
end

local function apiAvailable(api, methods)
    if type(api) ~= "table" then
        return false
    end

    for index = 1, #methods do
        if type(api[methods[index]]) ~= "function" then
            return false
        end
    end
    return true
end

local function enter(category, api, methods, operation)
    if RemoteRenderer.disabledCategories[category] ~= nil then
        return nil, "adapter-disabled"
    end

    if not apiAvailable(api, methods) then
        return nil, "api-unavailable"
    end

    local ok, result, reason = pcall(operation)
    if not ok then
        RemoteRenderer.disabledCategories[category] = "adapter-error"
        return nil, "adapter-error"
    end

    return result, reason
end

local function renderAction(api, remote, event)
    local methods = event.key == "read"
        and { "isLocalPlayer", "applyReadingState" }
        or { "isLocalPlayer", "applyAnimationVariable" }
    return enter("action", api, methods, function()
        if api.isLocalPlayer(remote) then
            return nil, "local-player"
        end

        local descriptor = ActionRegistry.get(event.key)
        if descriptor == nil or type(event.animationVars) ~= "table" then
            return nil, "action"
        end

        if event.key == "read" then
            local active = event.phase ~= "complete" and event.phase ~= "cancel"
            local readType = event.animationVars.ReadType
            if type(readType) ~= "string" or #readType > 64 then
                readType = nil
            end
            api.applyReadingState(remote, active, active and event.begin == true, readType)
            return true, "ok"
        end

        for index = 1, #descriptor.animationVars do
            local name = descriptor.animationVars[index]
            local value = event.animationVars[name]
            if value ~= nil and safeAnimationValue(value) then
                api.applyAnimationVariable(remote, name, value)
            end
        end

        return true, "ok"
    end)
end

function RemoteRenderer.apply(api, remote, event)
    if type(event) ~= "table" then
        return nil, "event"
    end

    if event.kind == "action" then
        return renderAction(api, remote, event)
    end

    if event.kind == "player" or event.kind == "vehicle" or event.kind == "trailer" then
        return nil, "native-authority"
    end

    return nil, "event"
end

return RemoteRenderer
