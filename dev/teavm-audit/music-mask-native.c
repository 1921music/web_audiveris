/* SPDX-License-Identifier: AGPL-3.0-or-later */
/* Shared comparison helper using public FreeType API; not linked into the TeaVM engine. */
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_MODULE_H
#include <stdlib.h>
#include <math.h>

struct MaskFace { FT_Library library; FT_Face face; };

int music_mask_version(int *version) {
    FT_Library library;
    FT_Error error = FT_Init_FreeType(&library);
    if (error) return error;
    FT_Library_Version(library,&version[0],&version[1],&version[2]);
    FT_Done_FreeType(library);
    return 0;
}

void *music_mask_open(const char *path) {
    struct MaskFace *ctx = calloc(1,sizeof(*ctx));
    if (!ctx) return NULL;
    if (FT_Init_FreeType(&ctx->library)) { free(ctx); return NULL; }
    unsigned int version = 35;
    if (FT_Property_Set(ctx->library,"truetype","interpreter-version",&version) ||
        FT_New_Face(ctx->library,path,0,&ctx->face)) {
        FT_Done_FreeType(ctx->library); free(ctx); return NULL;
    }
    return ctx;
}

void music_mask_close(void *handle) {
    struct MaskFace *ctx = handle;
    if (ctx) { FT_Done_Face(ctx->face); FT_Done_FreeType(ctx->library); free(ctx); }
}

int music_mask_render(void *handle,int glyph,double size,int no_hinting,int *info,
                      unsigned char *pixels,int capacity) {
    struct MaskFace *ctx = handle;
    if (!ctx || !isfinite(size) || size<=0 || size>128 || glyph<0 || capacity<0 || no_hinting<0 || no_hinting>1) return -1;
    FT_Error error = FT_Set_Char_Size(ctx->face,0,(FT_F26Dot6)(size*64),72,72);
    if (error) return error;
    FT_Int32 flags = FT_LOAD_NO_BITMAP | FT_LOAD_TARGET_NORMAL;
    if (no_hinting) flags |= FT_LOAD_NO_HINTING;
    error = FT_Load_Glyph(ctx->face,(FT_UInt)glyph,flags);
    if (error) return error;
    FT_GlyphSlot slot = ctx->face->glyph;
    error = FT_Render_Glyph(slot,FT_RENDER_MODE_NORMAL);
    if (error) return error;
    FT_Bitmap *bitmap = &slot->bitmap;
    if (bitmap->pixel_mode!=FT_PIXEL_MODE_GRAY || bitmap->num_grays!=256 ||
        bitmap->width>512 || bitmap->rows>512 ||
        (size_t)bitmap->width*bitmap->rows>(size_t)capacity) return -2;
    info[0]=slot->bitmap_left; info[1]=-slot->bitmap_top;
    info[2]=(int)bitmap->width; info[3]=(int)bitmap->rows;
    for (unsigned int y=0;y<bitmap->rows;y++) {
        const unsigned char *row = bitmap->buffer +
            (bitmap->pitch>=0 ? (int)y*bitmap->pitch : (int)(bitmap->rows-y-1)*-bitmap->pitch);
        for (unsigned int x=0;x<bitmap->width;x++) pixels[y*bitmap->width+x]=row[x];
    }
    return 0;
}
