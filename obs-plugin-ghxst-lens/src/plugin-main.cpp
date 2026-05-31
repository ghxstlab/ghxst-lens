#include <obs-module.h>

#include "ghxst-lens-source.hpp"

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("ghxst-lens", "en-US")

bool obs_module_load(void)
{
	obs_register_source(&ghxst_lens_source_info);

	blog(LOG_INFO, "[GHXST Lens] Plugin loaded");
	blog(LOG_INFO, "[GHXST Lens] v0.4-OBS4B cleanup/stability active");

	return true;
}

void obs_module_unload(void)
{
	blog(LOG_INFO, "[GHXST Lens] Plugin unloaded");
}
