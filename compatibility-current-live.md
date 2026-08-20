# Apollo multiplayer sync — current live compatibility contract

Live identity refreshed after the controlled 2026-08-20 rollout. The bounded
third-party compatibility scan below remains the 2026-08-08 snapshot; it was
not silently reclassified during the game update. This is a compatibility
boundary, not a copy of Workshop code.

## Live identity

| Field | Observed value |
| --- | --- |
| Server game version / revision | `42.20.3` / `70207f62e0` |
| Dedicated-server Steam BuildID | `24775771` |
| Active Workshop IDs | `85` |
| Active Mod IDs | `92` |
| Map | `Shadyside; Falcon Ridge; Falcon Ridge Road Ext; TheWarehouse; Muldraugh, KY` |
| DoLuaChecksum | `false` |

The exact version came from the current boot/debug-log marker (`version=42.20.3 70207f62e0`) and the BuildID from the live Steam appmanifest, not from an image tag. The running service uses the pinned image digest and the exact Native Assist `0.2.2` fingerprint described in `docs/compatibility-42.20.3.md`. The 85 configured Workshop IDs and 92 configured Mod IDs were counted from the active Compose file; the retained third-party rows below were not rescanned.

## Inspection method and limits

For each matching owner package, inspection was limited to `mod.info`, Build-42 `media/lua` filenames, `require`, `Events.*.Add`, `ISTimedActionQueue`/`ISBaseTimedAction`, vehicle/trailer/sleep tokens, and global-function assignment patterns. No third-party Lua was copied here.

`B42 resolution` below means the highest matching `mod.info` Build-42 subdirectory (`42.20` > `42.19` ... > `42`); packages with no numbered B42 directory resolve to `common` or the package root. This is intentional because Workshop packages ship multiple retained compatibility directories. “event” means an `Events.*.Add` registration was found; “player-update” is the narrower `Events.OnPlayerUpdate.Add` fact. `timed` is an `ISTimedActionQueue` or `ISBaseTimedAction` fact. An empty/`none` category means none of the enumerated compatibility hooks was found by this bounded scan—not that the mod has no behavior.

## Matrix

All dispositions are deliberately limited to the synchronization implementation's four public choices. `vanilla-fallback` is used for packages with vehicle/trailer behavior because no narrow safe adapter was proven in this read-only pass. `generic-semantic` preserves ordinary event/timed semantics without importing the package. Sleep with Friends has the explicit built-in adapter row required by the contract.

| Mod ID | Workshop ID | B42 resolution | Relevant hook fact | Disposition |
| --- | ---: | --- | --- | --- |
| ModLoadOrderSorter_b42 | 3423660713 | `42.0` | event | generic-semantic |
| ModManager | 3567084868 | `42.20` | event | generic-semantic |
| StarlitLibrary | 3378285185 | `42.15` | event; player-update; timed | generic-semantic |
| damnlib | 3171167894 | `42.17` | none | generic-semantic |
| KATTAJ1_ClothesCore | 3470422050 | `42.15` | event | generic-semantic |
| NeatUI_Framework | 3508537032 | `42` | none | generic-semantic |
| CleanUI | 3437629766 | `42.19` | event; timed | generic-semantic |
| NewEllroyShadysideB42 | 3747339147 | `42.0` | none | generic-semantic |
| FalconRidgeB42 | 3747335481 | `42.0` | none | generic-semantic |
| TheWarehouse | 3706715944 | `42.0` | none | generic-semantic |
| DG_MTJ | 3281859265 | `42` | event | vanilla-fallback |
| DBFaster50 | 3393821407 | `42` | none | generic-semantic |
| WayMoreCars | 3520758551 | `42` | event; player-update; timed; vehicle | vanilla-fallback |
| fhqMotoriousZone | 2791656602 | `42.15` | vehicle/trailer package evidence | vanilla-fallback |
| P42AnimalTrailersExorcised | 3462845821 | `common` | trailer package evidence | vanilla-fallback |
| MoreVarietyLoot25 | 3427382678 | `42` | none | generic-semantic |
| BVF42 | 3715977706 | `42` | none | generic-semantic |
| SpnOpenClothBase | 2812326159 | `42.18` | event | generic-semantic |
| SpnOpenCloth | 2812326159 | `42.18` | event | generic-semantic |
| SpnCloth | 2684285534 | `42.18` | none | generic-semantic |
| SPNCC | 3414634809 | `42.19` | event; player-update; timed | generic-semantic |
| SPNCCDetails | 3414634809 | `42.13` | event; player-update; timed | generic-semantic |
| SPNCCFaces | 3414634809 | `42.13` | event; player-update; timed | generic-semantic |
| KATTAJ1_Military | 3470426196 | `42.15` | event | generic-semantic |
| KMPRUS | 3494081739 | `42` | none | generic-semantic |
| ArmorMakesSense | 3677430162 | `42` | event; player-update | generic-semantic |
| VehicleRepairOverhaul | 2757712197 | `42` | event; timed; vehicle/trailer package evidence | vanilla-fallback |
| VehicleSalvageOverhaulB42 | 2757712197 | `42` | event; timed; vehicle/trailer package evidence | vanilla-fallback |
| AccCamos | 3454414190 | `42` | none | generic-semantic |
| NepCanteenSlot | 3393080905 | `42` | event | generic-semantic |
| GlassHats | 2423266708 | `42` | event | generic-semantic |
| WearProtectors | 3439298478 | `42` | none | generic-semantic |
| FunctionalEarMuffs | 3455253326 | `42` | event; player-update | generic-semantic |
| SomewhatTraitsCore | 3498347699 | `42.15` | event; player-update; timed | generic-semantic |
| SomewhatTraits | 3498347699 | `42.15` | event; player-update; timed | generic-semantic |
| twistnimblexp | 3732469752 | `42.18` | event; player-update | generic-semantic |
| LiquidsPZJournal | 3741565717 | `42` | event; timed | generic-semantic |
| SleepWithFriends | 2686624983 | `42.13` | event; sleep adapter | built-in-adapter |
| better-auto-mechanics | 3635856965 | `common` | timed; vehicle/trailer package evidence | vanilla-fallback |
| TaillightsAndStoplights | 3687394815 | `42` | event; player-update; vehicle/trailer package evidence | vanilla-fallback |
| FunctionalCarLift | 3464551542 | `42` | event; vehicle package evidence | vanilla-fallback |
| ReplaceBandage | 2944344655 | `42.13` | timed | generic-semantic |
| FAKRemodel | 2954837790 | `42` | none | generic-semantic |
| twistcamping | 3738162759 | `42.19` | event; timed | generic-semantic |
| NepNearbyTraps | 2974760428 | `42` | none | generic-semantic |
| RepairableWindows | 3378304610 | `42.13` | event; timed | generic-semantic |
| FunctionalGutters | 3439305933 | `42.15` | event; timed | generic-semantic |
| CleansingRain | 3723749278 | `42` | event | generic-semantic |
| ImmersiveBlackouts | 3607686447 | `42` | event | generic-semantic |
| BetterGeneratorInfo | 3576056135 | `42.20` | event; player-update | generic-semantic |
| KRCoreOS | 3714654032 | `42` | event | generic-semantic |
| KRSolarOS | 3771709541 | `42` | event; timed | generic-semantic |
| GasPumpIndicator | 3755993986 | `42` | event | generic-semantic |
| DEON_CVG | 3390487814 | `42` | none | generic-semantic |
| CVLS | 3719237374 | `common` | none | generic-semantic |
| eds_cvpt | 3725667439 | `common` | none | generic-semantic |
| ImprovedFarmingInfoWindow | 3470852353 | `42.0` | event | generic-semantic |
| mowingwithscythe | 3475536311 | `42` | event; timed | generic-semantic |
| NamedSkillVHSTapes | 2732294885 | `42` | none | generic-semantic |
| KillCount | 2553809727 | `42.0` | event; player-update | generic-semantic |
| EURY_CONTAINERS | 3586216562 | `42.20` | event; timed | generic-semantic |
| P4PickingMeister | 3422220305 | `42.20` | event; timed | generic-semantic |
| EQUIPMENT_UI | 2950902979 | `42.13` | event | generic-semantic |
| equipmentuipatch | 3682936016 | `42.15` | event | generic-semantic |
| MapLegendUI | 2710167561 | `42` | none | generic-semantic |
| PZShareMapNotes | 3676995511 | `42` | event | generic-semantic |
| CleanHotBar | 3461263912 | `42.15` | event | generic-semantic |
| REORDER_THE_HOTBAR | 2903771337 | `42.0` | event | generic-semantic |
| ModernStatus | 3451167732 | `42.13` | event | generic-semantic |
| Neat_Crafting | 3502080466 | `42.13` | event; timed | generic-semantic |
| Neat_Building_UIOnly | 3536052310 | `42.15` | event | generic-semantic |
| Neat_Building_AddonXP | 3540503606 | `42.15` | event | generic-semantic |
| Neat_Crafting_AddonXP | 3540503606 | `42.15` | event | generic-semantic |
| VanillaFoodsExpanded | 3577903007 | `42.18` | vehicle/trailer token evidence | generic-semantic |
| B42Horticulture | 3650168851 | `42.14` | event | generic-semantic |
| Project_Cook | 3490188370 | `42.15` | timed | generic-semantic |
| AutomaticStoveShutoff | 3526968739 | `42` | event | generic-semantic |
| TheShortcut | 3470659758 | `42.14` | event; timed | generic-semantic |
| DetailedDescriptionsForOccupationsAndTraits | 3387957272 | `42` | none | generic-semantic |
| MoodleEffectsExplainedB42 | 3747745835 | `42.20` | event | generic-semantic |
| ArcadiaQOLSafehouse_B42 | 3773933038 | `42` | event | generic-semantic |
| StopJumpingOffStairs | 3650112103 | `42` | event; player-update | generic-semantic |
| AllyLocator | 3742063520 | `42.0` | event | generic-semantic |
| ReloadAllMagazines | 2920899878 | `42.13` | timed | generic-semantic |
| SubirEscaleras | 3774776279 | `42` | event; timed | generic-semantic |
| JumboTreeIndoorFix | 3774826484 | `42` | event | generic-semantic |
| BetterLockpickingContinued | 3768298886 | `42` | timed | generic-semantic |
| SaucedCarts | 3651954650 | `42` | event; player-update; timed; vehicle/trailer package evidence | vanilla-fallback |
| PingItemsFriends | 3776262249 | `42` | event | generic-semantic |
| PingItemsFriendsDefaultWheel | 3776262249 | `42` | event | generic-semantic |
| ZomboidManager | 3685323705 | `42` | event | generic-semantic |
| ApolloMPSyncB42 | 3780069702 | `42` | own bounded Lua package plus exact server-only Native Assist | built-in-adapter |

## Evidence gaps and operating concerns

- The third-party row classifications are still the bounded 2026-08-08 static scan. The 2026-08-20 rollout revalidated Apollo against exact `42.20.3`; it did not assert that every third-party package had been re-reviewed for the patch.
- `DoLuaChecksum=false` is a live fact and a compatibility/security concern. This task did not change it.
- Workshop package searches are bounded static evidence. The controlled live update retains a separate private operator record with its rollback boundary.
