local AdaptiveRate = {}

local function clamp(value, lower, upper)
    if value < lower then
        return lower
    end

    if value > upper then
        return upper
    end

    return value
end

local function safePing(value)
    if type(value) ~= "number" or value ~= value or value == math.huge or value == -math.huge then
        return 0
    end

    return clamp(value, 0, 5000)
end

function AdaptiveRate.update(state, pingMs, kind)
    local mode = kind == "vehicle" and "vehicle" or "player"
    local ping = safePing(pingMs)
    local average = state[mode]

    if average == nil then
        average = ping
    else
        average = average + 0.2 * (ping - average)
    end

    state[mode] = average

    if mode == "vehicle" then
        return clamp(100 + 0.5 * average, 100, 250)
    end

    return clamp(150 + 0.5 * average, 150, 350)
end

return AdaptiveRate
