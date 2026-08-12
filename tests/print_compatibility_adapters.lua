package.path = "./?.lua;./?/init.lua;workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/?.lua;" .. package.path

local ActionRegistry = require("ApolloMPSync/ActionRegistry")
local adapters = ActionRegistry.compatibilityAdapters()

table.sort(adapters, function(left, right)
    return left.modId < right.modId
end)

for index = 1, #adapters do
    local adapter = adapters[index]
    print(adapter.modId .. "|" .. adapter.workshopId .. "|" .. adapter.disposition)
end
