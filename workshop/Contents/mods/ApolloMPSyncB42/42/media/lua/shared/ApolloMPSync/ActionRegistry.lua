local ActionRegistry = {}

local allowedCategories = {
    sleep = true,
    read = true,
    sit = true,
    rest = true,
    ["vehicle-enter"] = true,
    ["vehicle-exit"] = true,
    ["climb-window"] = true,
    ["climb-fence"] = true,
    ["climb-ladder"] = true,
    ["floor-transition"] = true,
    ["timed-action"] = true
}

local allowedAnimationVars = {
    PerformingAction = true,
    IsReading = true,
    ReadType = true,
    isSitOnGround = true,
    ClimbFence = true,
    ClimbWindow = true,
    ClimbLadder = true,
    VehicleTransition = true
}

local allowedAnchorModes = {
    player = true,
    vehicle = true,
    none = true
}

local allowedFields = {
    key = true,
    category = true,
    classes = true,
    animationVars = true,
    anchorMode = true
}

local genericBusy = {
    key = "generic-busy",
    category = "timed-action",
    classes = {},
    animationVars = { "PerformingAction" },
    anchorMode = "player"
}

local byKey = { [genericBusy.key] = genericBusy }
local byClass = {}
local compatibilityAdapters = {
    {
        modId = "SleepWithFriends",
        workshopId = "2686624983",
        disposition = "built-in-adapter",
        actionKey = "sleep",
        classes = { "ISSleepWithFriendsAction" }
    }
}
local compatibilityByModId = { SleepWithFriends = compatibilityAdapters[1] }

local function copyList(source)
    local copy = {}
    for index = 1, #source do
        copy[index] = source[index]
    end
    return copy
end

local function copyDescriptor(descriptor)
    return {
        key = descriptor.key,
        category = descriptor.category,
        classes = copyList(descriptor.classes),
        animationVars = copyList(descriptor.animationVars),
        anchorMode = descriptor.anchorMode
    }
end

local function copyCompatibilityAdapter(adapter)
    return {
        modId = adapter.modId,
        workshopId = adapter.workshopId,
        disposition = adapter.disposition,
        actionKey = adapter.actionKey,
        classes = copyList(adapter.classes)
    }
end

local function validKey(key)
    return type(key) == "string"
        and key:match("^[a-z][a-z0-9%-]*$") ~= nil
end

local function validStringList(value, allowed)
    if type(value) ~= "table" then
        return false
    end

    local seen = {}
    local count = 0
    for index, entry in pairs(value) do
        if type(index) ~= "number" or index ~= math.floor(index) or index < 1
            or type(entry) ~= "string" or entry == "" or seen[entry]
            or (allowed ~= nil and not allowed[entry]) then
            return false
        end
        seen[entry] = true
        count = count + 1
    end

    return count == #value
end

local function validateDescriptor(key, descriptor)
    if not validKey(key) or type(descriptor) ~= "table" then
        return false, "descriptor"
    end

    for field in pairs(descriptor) do
        if not allowedFields[field] then
            return false, "descriptor"
        end
    end

    if descriptor.key ~= key or not allowedCategories[descriptor.category]
        or not validStringList(descriptor.classes)
        or not validStringList(descriptor.animationVars, allowedAnimationVars)
        or not allowedAnchorModes[descriptor.anchorMode] then
        return false, "descriptor"
    end

    return true, "ok"
end

function ActionRegistry.register(key, descriptor)
    local valid, reason = validateDescriptor(key, descriptor)
    if not valid then
        return false, reason
    end

    if byKey[key] ~= nil then
        return false, "duplicate-key"
    end

    for index = 1, #descriptor.classes do
        if byClass[descriptor.classes[index]] ~= nil then
            return false, "duplicate-class"
        end
    end

    local stored = copyDescriptor(descriptor)
    byKey[key] = stored
    for index = 1, #stored.classes do
        byClass[stored.classes[index]] = stored
    end

    return true, "ok"
end

function ActionRegistry.resolve(className)
    local descriptor = type(className) == "string" and byClass[className] or nil
    if descriptor == nil then
        return copyDescriptor(genericBusy)
    end

    return copyDescriptor(descriptor)
end

function ActionRegistry.get(key)
    local descriptor = byKey[key]
    if descriptor == nil then
        return nil
    end

    return copyDescriptor(descriptor)
end

function ActionRegistry.categories()
    local categories = {}
    for category in pairs(allowedCategories) do
        categories[#categories + 1] = category
    end
    table.sort(categories)
    return categories
end

function ActionRegistry.compatibilityAdapters()
    local copy = {}
    for index = 1, #compatibilityAdapters do
        copy[index] = copyCompatibilityAdapter(compatibilityAdapters[index])
    end
    return copy
end

function ActionRegistry.compatibilityAdapter(modId)
    local adapter = type(modId) == "string" and compatibilityByModId[modId] or nil
    if adapter == nil then
        return nil
    end
    return copyCompatibilityAdapter(adapter)
end

local function registerClasses(key, classes, idempotent)
    if not validStringList(classes) or #classes == 0 then
        return false, "classes"
    end

    local descriptor = byKey[key]
    if descriptor == nil then
        return false, "descriptor"
    end

    for index = 1, #classes do
        local existing = byClass[classes[index]]
        if existing ~= nil and (not idempotent or existing ~= descriptor) then
            return false, "duplicate-class"
        end
    end

    for index = 1, #classes do
        local className = classes[index]
        if byClass[className] == nil then
            descriptor.classes[#descriptor.classes + 1] = className
            byClass[className] = descriptor
        end
    end

    return true, "ok"
end

function ActionRegistry.activateCompatibilityAdapter(modId)
    local adapter = type(modId) == "string" and compatibilityByModId[modId] or nil
    if adapter == nil then
        return false, "adapter"
    end

    return registerClasses(adapter.actionKey, adapter.classes, true)
end

function ActionRegistry.registerSleepWithFriends(classes)
    return registerClasses("sleep", classes, false)
end

local function builtin(key, classes, animationVars, anchorMode)
    local registered, reason = ActionRegistry.register(key, {
        key = key,
        category = key,
        classes = classes,
        animationVars = animationVars,
        anchorMode = anchorMode
    })

    if not registered then
        error("unable to register built-in action " .. key .. ": " .. reason)
    end
end

builtin("sleep", { "ISSleepAction" }, { "PerformingAction" }, "player")
builtin("read", { "ISReadABook" }, { "PerformingAction", "IsReading", "ReadType" }, "player")
builtin("sit", { "ISSitOnGround" }, { "PerformingAction", "isSitOnGround" }, "player")
builtin("rest", { "ISRestAction" }, { "PerformingAction" }, "player")
builtin("vehicle-enter", { "ISEnterVehicle" }, { "PerformingAction", "VehicleTransition" }, "vehicle")
builtin("vehicle-exit", { "ISExitVehicle" }, { "PerformingAction", "VehicleTransition" }, "vehicle")
builtin("climb-window", { "ISClimbThroughWindow" }, { "PerformingAction", "ClimbWindow" }, "player")
builtin("climb-fence", { "ISClimbOverFence" }, { "PerformingAction", "ClimbFence" }, "player")
builtin("climb-ladder", { "ISClimbLadder" }, { "PerformingAction", "ClimbLadder" }, "player")
builtin("floor-transition", { "ISFloorTransition" }, { "PerformingAction" }, "player")
builtin("timed-action", { "ISBaseTimedAction" }, { "PerformingAction" }, "player")

return ActionRegistry
