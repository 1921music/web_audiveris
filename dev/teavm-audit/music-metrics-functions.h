/* SPDX-License-Identifier: AGPL-3.0-or-later */
/* Included after the shared mask API; all returned metrics are unhinted. */
#include FT_BBOX_H
#include FT_SFNT_NAMES_H
#include <string.h>

const char *music_metrics_family(void *handle) {
    struct MaskFace *ctx = handle;
    return ctx ? ctx->face->family_name : NULL;
}

int music_metrics_full_name(void *handle,unsigned char *output,int capacity) {
    struct MaskFace *ctx = handle;
    if (!ctx || !output || capacity<=0) return -1;
    FT_SfntName chosen={0}; int best=100;
    for (FT_UInt index=0;index<FT_Get_Sfnt_Name_Count(ctx->face);index++) {
        FT_SfntName name;
        if (FT_Get_Sfnt_Name(ctx->face,index,&name) || name.name_id!=4) continue;
        int unicode=name.platform_id==0 || (name.platform_id==3 && (name.encoding_id==1 || name.encoding_id==10));
        if (!unicode || name.string_len%2) continue;
        int score=name.platform_id==3 && name.language_id==0x409 ? 0 : name.platform_id==0 ? 1 : 2;
        if (score<best) { chosen=name;best=score; }
    }
    if (best==100) {
        const char *name=ctx->face->family_name;
        if (!name || strlen(name)>=(size_t)capacity) return -1;
        memcpy(output,name,strlen(name)+1);return (int)strlen(name);
    }
    int length=0;
    for (FT_UInt index=0;index<chosen.string_len;index+=2) {
        unsigned int code=(unsigned int)chosen.string[index]*256+chosen.string[index+1];
        if (code>=0xd800 && code<=0xdbff) {
            if (index+3>=chosen.string_len) return -1;
            unsigned int low=(unsigned int)chosen.string[index+2]*256+chosen.string[index+3];
            if (low<0xdc00 || low>0xdfff) return -1;
            code=0x10000+(code-0xd800)*1024+low-0xdc00;index+=2;
        } else if (code>=0xdc00 && code<=0xdfff) return -1;
        int bytes=code<0x80 ? 1 : code<0x800 ? 2 : code<0x10000 ? 3 : 4;
        if (!code || length+bytes>=capacity) return -1;
        if (bytes==1) output[length++]=(unsigned char)code;
        else {
            output[length++]=(unsigned char)((bytes==2 ? 0xc0 : bytes==3 ? 0xe0 : 0xf0) | (code>>(6*(bytes-1))));
            for (int shift=6*(bytes-2);shift>=0;shift-=6) output[length++]=(unsigned char)(0x80|((code>>shift)&0x3f));
        }
    }
    output[length]=0;return length;
}

int music_metrics_glyph(void *handle,int codepoint) {
    struct MaskFace *ctx = handle;
    if (!ctx || codepoint<0 || codepoint>0x10ffff) return -1;
    return (int)FT_Get_Char_Index(ctx->face,(FT_ULong)codepoint);
}

int music_metrics_measure(void *handle,int glyph,int size,double *values) {
    struct MaskFace *ctx = handle;
    if (!ctx || glyph<0 || size<=0 || size>16384 || !values) return -1;
    FT_Error error = FT_Set_Char_Size(ctx->face,0,(FT_F26Dot6)size*64,72,72);
    if (error) return error;
    error = FT_Load_Glyph(ctx->face,(FT_UInt)glyph,FT_LOAD_NO_HINTING|FT_LOAD_NO_BITMAP);
    if (error) return error;
    FT_GlyphSlot slot = ctx->face->glyph;
    if (slot->format!=FT_GLYPH_FORMAT_OUTLINE) return -2;
    FT_BBox box;
    error = FT_Outline_Get_BBox(&slot->outline,&box);
    if (error) return error;
    values[0]=values[1]=values[2]=values[3]=0;
    if (slot->outline.n_points!=0 && box.xMin<box.xMax && box.yMin<box.yMax) {
        values[0]=(float)(box.xMin/64.0f); values[1]=(float)(-box.yMax/64.0f);
        values[2]=(float)((box.xMax-box.xMin)/64.0f); values[3]=(float)((box.yMax-box.yMin)/64.0f);
    }
    values[4]=(float)(slot->linearHoriAdvance/65536.0f);
    return 0;
}
