"""Generate a deterministic native Xcode project without storing signing identities."""
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parent
APP_NAME = "TennisVisionCamera"

def identifier(value):
    return hashlib.sha256(value.encode()).hexdigest()[:24].upper()

def generate():
    shared = ROOT.parent/'shared/release.json'
    resource = ROOT/'App/Resources/MobileRelease.json'
    contract = json.loads((shared if shared.is_file() else resource).read_text(encoding='utf-8'))
    if contract['version'] != '0.6.9' or set(contract['platforms']) != {'android','ios'}:
        raise ValueError('Android/iOS feature release must be coordinated')
    android = ROOT.parent/'android/app/build.gradle.kts'
    if android.is_file() and re.search(r'versionName\s*=\s*"([0-9.]+)"',android.read_text(encoding='utf-8')).group(1) != contract['version']:
        raise ValueError('Port and validate both apps before changing the shared feature release')
    resource.parent.mkdir(parents=True,exist_ok=True)
    resource.write_text(json.dumps(contract,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    fixtures=ROOT.parent/'shared/vision-cases.json'
    if fixtures.is_file():
        testFixtures=ROOT/'Tests/TennisVisionCoreTests/Fixtures/vision-cases.json'
        testFixtures.parent.mkdir(parents=True,exist_ok=True)
        testFixtures.write_bytes(fixtures.read_bytes())
    sources = sorted(ROOT.glob("App/*.swift")) + sorted(ROOT.glob("Sources/TennisVisionCore/*.swift"))
    resources = [ROOT/"App/Assets.xcassets", ROOT/"App/PrivacyInfo.xcprivacy"] + sorted(ROOT.glob("App/Resources/*"))
    objects = []
    def add(key, value):
        uid = identifier(key)
        objects.append(f"\t\t{uid} = {{ {value} }};")
        return uid
    def q(value):
        return json.dumps(str(value), ensure_ascii=False)
    def ids(values):
        return "("+", ".join(values)+",)"
    source_refs, resource_refs, source_builds, resource_builds = [], [], [], []
    for files, refs, builds, kind in [(sources, source_refs, source_builds, "sourcecode.swift"),
                                      (resources, resource_refs, resource_builds, None)]:
        for path in files:
            relative = path.relative_to(ROOT).as_posix()
            file_kind = kind or ("folder.assetcatalog" if path.suffix == ".xcassets" else "audio.mp3" if path.suffix == ".mp3" else "text.json" if path.suffix == ".json" else "text.xml")
            ref = add(relative, f"isa = PBXFileReference; lastKnownFileType = {file_kind}; path = {q(relative)}; sourceTree = \"<group>\";")
            refs.append(ref)
            builds.append(add("build:"+relative, f"isa = PBXBuildFile; fileRef = {ref};"))
    product = add("product", f"isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = {APP_NAME}.app; sourceTree = BUILT_PRODUCTS_DIR;")
    sources_phase = add("sources", f"isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = {ids(source_builds)}; runOnlyForDeploymentPostprocessing = 0;")
    resources_phase = add("resources", f"isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = {ids(resource_builds)}; runOnlyForDeploymentPostprocessing = 0;")
    frameworks = add("frameworks", "isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;")
    product_group = add("products", f"isa = PBXGroup; children = ({product},); name = Products; sourceTree = \"<group>\";")
    main_group = add("main", f"isa = PBXGroup; children = {ids(source_refs+resource_refs+[product_group])}; sourceTree = \"<group>\";")
    configurations = {}
    for mode in ("Debug", "Release"):
        project_settings = {
            "CLANG_ENABLE_MODULES": "YES", "CLANG_ENABLE_OBJC_ARC": "YES",
            "SDKROOT": "iphoneos", "IPHONEOS_DEPLOYMENT_TARGET": "17.4",
            "SWIFT_VERSION": "5.0", "ENABLE_TESTABILITY": "YES" if mode == "Debug" else "NO",
            "SWIFT_OPTIMIZATION_LEVEL": "-Onone" if mode == "Debug" else "-O",
            "DEBUG_INFORMATION_FORMAT": "dwarf" if mode == "Debug" else "dwarf-with-dsym",
        }
        target_settings = {
            "PRODUCT_NAME": "$(TARGET_NAME)", "PRODUCT_BUNDLE_IDENTIFIER": "ai.tennisvision.camera.ios",
            "MARKETING_VERSION": "0.6.9", "CURRENT_PROJECT_VERSION": "10",
            "GENERATE_INFOPLIST_FILE": "NO", "INFOPLIST_FILE": "App/Info.plist",
            "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon", "CODE_SIGN_STYLE": "Automatic",
            "TARGETED_DEVICE_FAMILY": "1,2", "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator",
            "SUPPORTS_MACCATALYST": "NO", "ENABLE_USER_SCRIPT_SANDBOXING": "YES",
            "SWIFT_EMIT_LOC_STRINGS": "YES",
        }
        def settings(values):
            return " ".join(f"{key} = {q(value)};" for key, value in values.items())
        configurations["project:"+mode] = add("project:"+mode,
            f"isa = XCBuildConfiguration; buildSettings = {{ {settings(project_settings)} }}; name = {mode};")
        configurations["target:"+mode] = add("target:"+mode,
            f"isa = XCBuildConfiguration; buildSettings = {{ {settings(target_settings)} }}; name = {mode};")
    def config_list(prefix):
        return add(prefix+":configs", f"isa = XCConfigurationList; buildConfigurations = {ids([configurations[prefix+':Debug'],configurations[prefix+':Release']])}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;")
    target_configs, project_configs = config_list("target"), config_list("project")
    target = add("target", f"isa = PBXNativeTarget; buildConfigurationList = {target_configs}; buildPhases = ({sources_phase}, {frameworks}, {resources_phase},); buildRules = (); dependencies = (); name = {APP_NAME}; productName = {APP_NAME}; productReference = {product}; productType = \"com.apple.product-type.application\";")
    project = add("project", f"isa = PBXProject; attributes = {{ BuildIndependentTargetsInParallel = YES; LastUpgradeCheck = 1600; }}; buildConfigurationList = {project_configs}; compatibilityVersion = \"Xcode 14.0\"; developmentRegion = pt; hasScannedForEncodings = 0; knownRegions = (pt, en, Base,); mainGroup = {main_group}; productRefGroup = {product_group}; projectDirPath = \"\"; projectRoot = \"\"; targets = ({target},);")
    destination = ROOT/f"{APP_NAME}.xcodeproj"
    destination.mkdir(exist_ok=True)
    (destination/"project.pbxproj").write_text("// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {};\n\tobjectVersion = 56;\n\tobjects = {\n"+"\n".join(objects)+f"\n\t}};\n\trootObject = {project};\n}}\n", encoding="utf-8", newline="\n")
    schemes = destination/"xcshareddata/xcschemes"
    schemes.mkdir(parents=True, exist_ok=True)
    reference = f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="{APP_NAME}.app" BlueprintName="{APP_NAME}" ReferencedContainer="container:{APP_NAME}.xcodeproj"/>'
    (schemes/f"{APP_NAME}.xcscheme").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{reference}</BuildActionEntry></BuildActionEntries></BuildAction>
  <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables/></TestAction>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{reference}</BuildableProductRunnable></LaunchAction>
  <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugServiceExtension="internal"><BuildableProductRunnable runnableDebuggingMode="0">{reference}</BuildableProductRunnable></ProfileAction>
  <AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
''', encoding="utf-8", newline="\n")

if __name__ == "__main__":
    generate()
    print("Xcode project generated. Select your own signing team in Xcode for installation.")
