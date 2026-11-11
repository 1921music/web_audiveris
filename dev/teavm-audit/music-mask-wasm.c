/* SPDX-License-Identifier: AGPL-3.0-or-later */
/* Candidate component only; the caller owns the font bytes until music_mask_close. */
#include "music-mask-native.c"

void *music_mask_memory_open(const unsigned char *bytes,int length) {
    if (!bytes || length<=0 || length>16*1024*1024) return NULL;
    struct MaskFace *ctx = calloc(1,sizeof(*ctx));
    if (!ctx) return NULL;
    if (FT_Init_FreeType(&ctx->library)) { free(ctx); return NULL; }
    unsigned int version = 35;
    if (FT_Property_Set(ctx->library,"truetype","interpreter-version",&version) ||
        FT_New_Memory_Face(ctx->library,bytes,length,0,&ctx->face)) {
        FT_Done_FreeType(ctx->library); free(ctx); return NULL;
    }
    return ctx;
}
