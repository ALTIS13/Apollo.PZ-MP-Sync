local NativeAssistClient = {}

local FALLBACK = {
    available = false,
    mode = "fallback",
    authority = "vanilla",
    reason = "bridge-unavailable"
}

local function copy(value)
    local result = {}
    for key, entry in pairs(value) do result[key] = entry end
    return result
end

local function publishedStatus(api)
    if type(api) == "table" and type(api.getNativeAssistStatus) == "function" then
        local ok, value = pcall(api.getNativeAssistStatus)
        if ok and type(value) == "table" then return value end
    end
    local value = rawget(_G, "ApolloNativeAssistStatus")
    return type(value) == "table" and value or nil
end

local function validOptionalString(value, maximum)
    return value == nil
        or (type(value) == "string" and #value >= 1 and #value <= maximum)
end

function NativeAssistClient.status(api)
    local published = publishedStatus(api)
    if published == nil
        or published.available ~= true
        or published.mode ~= "native"
        or published.authority ~= "server"
        or not validOptionalString(published.reason, 128)
        or not validOptionalString(published.detail, 256) then
        return copy(FALLBACK)
    end

    local status = { available = true, mode = "native", authority = "server" }
    if published.reason ~= nil then status.reason = published.reason end
    if published.detail ~= nil then status.detail = published.detail end
    return status
end

return NativeAssistClient
