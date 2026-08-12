local WorkBudget = {}

local function isFinite(value)
    return type(value) == "number"
        and value == value
        and value ~= math.huge
        and value ~= -math.huge
end

function WorkBudget.drain(queue, maxItems, fn)
    if type(queue) ~= "table" or type(fn) ~= "function" or not isFinite(maxItems) then
        return 0
    end

    local budget = math.max(0, math.floor(maxItems))
    local processed = 0
    while processed < budget and #queue > 0 do
        local item = table.remove(queue, 1)
        fn(item)
        processed = processed + 1
    end

    return processed
end

return WorkBudget
