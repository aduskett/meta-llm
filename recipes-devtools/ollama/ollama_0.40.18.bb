SUMMARY = "Local LLM models"
DESCRIPTION = "Start building with open models. Access the latest releases locally or in the cloud with complete privacy."
HOMEPAGE = "https://ollama.com"
BUGTRACKER = "https://github.com/ollama/ollama/issues"
CVE_PRODUCT = "ollama"
SECTION = "devtools"

LICENSE = "MIT"
LIC_FILES_CHKSUM = "\
    file://src/${GO_IMPORT}/LICENSE;md5=a8abe7311c869aba169d640cf367a4af \
    file://llama.cpp/LICENSE;md5=223b26b3c1143120c87e2b13111d3e99 \
"
require ${BPN}-licenses.inc

# The llama.cpp revision is pinned by LLAMA_CPP_VERSION in the ollama sources.
SRC_URI = "\
    git://github.com/ollama/ollama;protocol=https;branch=main;tag=v${PV};destsuffix=${GO_SRCURI_DESTSUFFIX} \
    git://github.com/ggml-org/llama.cpp;protocol=https;branch=master;tag=b12651;name=llamacpp;destsuffix=${BP}/llama.cpp \
    file://0001-cmake-add-LLAMA_UNITY_BUILD-option.patch;patchdir=llama.cpp \
    file://ollama.service \
"
SRCREV = "924cc286cc592f49c481fa84e15fa60dbc75989e"
SRCREV_llamacpp = "f5e6915c28f10330885fcb9243a93ae98d3115ab"
SRCREV_FORMAT = "default_llamacpp"
require ${BPN}-go-mods.inc

inherit go-mod go-mod-update-modules cmake systemd useradd

GO_IMPORT = "github.com/ollama/ollama"
GO_INSTALL = "${GO_IMPORT}"
GO_EXTRA_LDFLAGS = "\
    -X=${GO_IMPORT}/version.Version=${PV} \
    -X=${GO_IMPORT}/server.mode=release \
"

OLLAMA_SRC = "${S}/src/${GO_IMPORT}"

# Build the llama-server runner directly instead of the upstream superbuild,
# which fetches llama.cpp at configure time.
OECMAKE_SOURCEPATH = "${OLLAMA_SRC}/llama/server"
EXTRA_OECMAKE = "\
    -DFETCHCONTENT_FULLY_DISCONNECTED=ON \
    -DFETCHCONTENT_SOURCE_DIR_LLAMA_CPP=${S}/llama.cpp \
    -DOLLAMA_LLAMA_CPP_SKIP_COMPAT_PATCH=ON \
    -DBUILD_SHARED_LIBS=ON \
    -DGGML_BACKEND_DL=ON \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DLLAMA_UNITY_BUILD=OFF \
"

PACKAGECONFIG ??= "${@bb.utils.filter('DISTRO_FEATURES', 'vulkan', d)}"
PACKAGECONFIG[vulkan] = "-DGGML_VULKAN=ON,-DGGML_VULKAN=OFF,vulkan-loader vulkan-headers spirv-headers shaderc-native"

# ggml forces its own -march on riscv64, so derive the extensions from the tune.
EXTRA_OECMAKE:append:riscv64 = " \
    -DGGML_RVV=${@bb.utils.contains('TUNE_FEATURES', 'v', 'ON', 'OFF', d)} \
    -DGGML_RV_ZBA=${@bb.utils.contains('TUNE_FEATURES', 'zba', 'ON', 'OFF', d)} \
    -DGGML_RV_ZFH=${@bb.utils.contains('TUNE_FEATURES', 'zfh', 'ON', 'OFF', d)} \
    -DGGML_RV_ZVFH=${@bb.utils.contains('TUNE_FEATURES', 'zvfh', 'ON', 'OFF', d)} \
    -DGGML_RV_ZVFBFWMA=${@bb.utils.contains('TUNE_FEATURES', 'zvfbfwma', 'ON', 'OFF', d)} \
    -DGGML_RV_ZICBOP=${@bb.utils.contains('TUNE_FEATURES', 'zicbop', 'ON', 'OFF', d)} \
    -DGGML_RV_ZIHINTPAUSE=${@bb.utils.contains('TUNE_FEATURES', 'zihintpause', 'ON', 'OFF', d)} \
"

ollama_patch_llama_cpp[doc] = "Apply the ollama compat hooks to the pinned llama.cpp source"
do_patch[postfuncs] += "ollama_patch_llama_cpp"
ollama_patch_llama_cpp() {
    patch -d ${S}/llama.cpp -p1 < ${OLLAMA_SRC}/llama/compat/001-llama-cpp-hooks.patch
}

do_configure() {
    cmake_do_configure
    ln -snf ${S}/src ${B}/
}

do_compile() {
    cmake_do_compile
    export TMPDIR="${GOTMPDIR}"
    ${GO} build ${GOBUILDFLAGS} -o ${B}/ollama .
}

do_install() {
    DESTDIR=${D} cmake --install ${B} --component llama-server
    # Unversioned symlinks are only needed for linking against the libraries.
    find ${D}${nonarch_libdir}/ollama -name 'lib*.so' -type l -delete
    install -D -m 0755 ${B}/ollama ${D}${bindir}/ollama

    # ollama discovers GPU backends in subdirectories of lib/ollama.
    if ${@bb.utils.contains('PACKAGECONFIG', 'vulkan', 'true', 'false', d)}; then
        install -D -m 0755 ${B}/bin/libggml-vulkan.so ${D}${nonarch_libdir}/ollama/vulkan/libggml-vulkan.so
    fi

    install -D -m 0644 ${UNPACKDIR}/ollama.service ${D}${systemd_system_unitdir}/ollama.service
    sed -i -e 's,@BINDIR@,${bindir},g' ${D}${systemd_system_unitdir}/ollama.service
}

USERADD_PACKAGES = "${PN}"
USERADD_PARAM:${PN} = "--system --user-group ${@bb.utils.contains('PACKAGECONFIG', 'vulkan', '--groups render,video', '', d)} --home-dir ${localstatedir}/lib/ollama --no-create-home --shell /sbin/nologin ollama"

SYSTEMD_SERVICE:${PN} = "ollama.service"

# ollama looks for its runner in ../lib/ollama relative to the binary.
FILES:${PN} += "${nonarch_libdir}/ollama"
