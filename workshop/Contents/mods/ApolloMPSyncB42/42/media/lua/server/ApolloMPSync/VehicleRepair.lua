local VehiclePolicy = require("ApolloMPSync/VehiclePolicy")
local WorkBudget = require("ApolloMPSync/WorkBudget")

local VehicleRepair = {}
VehicleRepair.__index = VehicleRepair

local function finite(value)
    return type(value) == "number"
        and value == value
        and value ~= math.huge
        and value ~= -math.huge
end

function VehicleRepair.new(api, maxVehiclesPerTick)
    return setmetatable({
        api = api,
        maxVehiclesPerTick = finite(maxVehiclesPerTick) and math.max(0, math.floor(maxVehiclesPerTick)) or 4,
        queue = {},
        queuedByVehicleId = {},
        fallbacks = 0
    }, VehicleRepair)
end

function VehicleRepair:enqueue(vehicleId, partId, condition)
    local validVehicleId = (type(vehicleId) == "string" and vehicleId ~= "")
        or (finite(vehicleId) and vehicleId > 0 and vehicleId == math.floor(vehicleId))
    if not validVehicleId
        or not VehiclePolicy.isVisualPartAllowed(partId)
        or not finite(condition) or condition < 0 or condition > 100 then
        return false, "part"
    end

    local job = self.queuedByVehicleId[vehicleId]
    if job == nil then
        job = { vehicleId = vehicleId, parts = {} }
        self.queuedByVehicleId[vehicleId] = job
        self.queue[#self.queue + 1] = job
    end
    job.parts[partId] = math.floor(condition + 0.5)
    return true, "ok"
end

function VehicleRepair:drain()
    local api = self.api
    local processed = WorkBudget.drain(self.queue, self.maxVehiclesPerTick, function(job)
        self.queuedByVehicleId[job.vehicleId] = nil
        if type(api) ~= "table" or type(api.getVehicleById) ~= "function"
            or type(api.applyVehiclePart) ~= "function" then
            self.fallbacks = self.fallbacks + 1
            return
        end

        local vehicle = api.getVehicleById(job.vehicleId)
        if vehicle == nil then
            self.fallbacks = self.fallbacks + 1
            return
        end

        for partId, condition in pairs(job.parts) do
            local ok, applied = pcall(api.applyVehiclePart, vehicle, partId, condition)
            if not ok or applied == false then
                self.fallbacks = self.fallbacks + 1
            end
        end
    end)
    return processed
end

function VehicleRepair:pendingVehicleCount()
    return #self.queue
end

return VehicleRepair
