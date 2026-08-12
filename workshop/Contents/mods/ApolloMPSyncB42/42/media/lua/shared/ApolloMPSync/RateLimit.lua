local RateLimit = {}

local function isFinite(value)
    return type(value) == "number"
        and value == value
        and value ~= math.huge
        and value ~= -math.huge
end

function RateLimit.accept(state, key, nowMs, minMs)
    if type(key) ~= "string" or key == "" then
        return false, "key"
    end

    if not isFinite(nowMs) or not isFinite(minMs) or minMs < 0 then
        return false, "time"
    end

    local minimum = math.max(100, minMs)
    local previous = state[key]

    if previous == nil then
        state[key] = nowMs
        return true, "first"
    end

    if nowMs - previous < minimum then
        return false, "rate"
    end

    state[key] = nowMs
    return true, "ok"
end

return RateLimit
