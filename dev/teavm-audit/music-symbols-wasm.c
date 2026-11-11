/* SPDX-License-Identifier: AGPL-3.0-or-later */
#define music_mask_render music_symbol_base_mask_render
#include "music-metrics-wasm.c"
#undef music_mask_render
#include FT_OUTLINE_H

/* JDK scaler normalizes sub-point fonts to one point plus a 16.16 transform. */
static float symbol_transform(struct MaskFace *ctx,double size) {
    float scale=size<1?(float)((FT_Fixed)((float)size*65536.0f))/65536.0f:1.0f;
    FT_Matrix matrix={(FT_Fixed)(scale*65536.0f),0,0,(FT_Fixed)(scale*65536.0f)};
    FT_Set_Transform(ctx->face,&matrix,NULL);return scale;
}

int music_mask_render(void *handle,int glyph,double size,int no_hinting,int *info,unsigned char *pixels,int capacity) {
    struct MaskFace *ctx=handle;
    if(!ctx||!isfinite(size)||size<=0||size>128)return -1;
    symbol_transform(ctx,size);
    int error=music_symbol_base_mask_render(handle,glyph,size<1?1:size,no_hinting,info,pixels,capacity);
    FT_Set_Transform(ctx->face,NULL,NULL);return error;
}

int music_symbol_glyph(void *handle,int codepoint) {
    struct MaskFace *ctx=handle;if(!ctx||codepoint<0||codepoint>0x10ffff)return -1;
    FT_CharMap previous=ctx->face->charmap;
    for(int i=0;i<ctx->face->num_charmaps;i++){
        FT_CharMap map=ctx->face->charmaps[i];
        if(map->platform_id==3&&map->encoding_id==0){
            if(FT_Set_Charmap(ctx->face,map))return -1;
            FT_UInt id=FT_Get_Char_Index(ctx->face,(FT_ULong)(codepoint<256?codepoint+0xf000:codepoint));
            if(!id&&codepoint<256)id=FT_Get_Char_Index(ctx->face,(FT_ULong)codepoint);
            if(previous)FT_Set_Charmap(ctx->face,previous);
            return (int)id;
        }
    }
    return (int)FT_Get_Char_Index(ctx->face,(FT_ULong)codepoint);
}

int music_symbol_metrics(void *handle,int glyph,double size,double *values) {
    struct MaskFace *ctx=handle;
    if(!ctx||glyph<0||!isfinite(size)||size<=0||size>4096||!values)return -1;
    float scale=symbol_transform(ctx,size);
    FT_Error error=FT_Set_Char_Size(ctx->face,0,(FT_F26Dot6)((size<1?1:size)*64),72,72);
    FT_Set_Transform(ctx->face,NULL,NULL);
    if(error)return error;
    symbol_transform(ctx,size);
    error=FT_Load_Glyph(ctx->face,(FT_UInt)glyph,FT_LOAD_NO_HINTING|FT_LOAD_NO_BITMAP);
    FT_Set_Transform(ctx->face,NULL,NULL);
    if(error)return error;
    FT_GlyphSlot slot=ctx->face->glyph;FT_BBox box;
    if(slot->format!=FT_GLYPH_FORMAT_OUTLINE)return -2;
    error=FT_Outline_Get_BBox(&slot->outline,&box);if(error)return error;
    values[0]=values[1]=values[2]=values[3]=0;
    if(slot->outline.n_points&&box.xMin<box.xMax&&box.yMin<box.yMax){
        values[0]=(float)(box.xMin/64.0f);values[1]=(float)(-box.yMax/64.0f);
        values[2]=(float)((box.xMax-box.xMin)/64.0f);values[3]=(float)((box.yMax-box.yMin)/64.0f);
    }
    values[4]=(float)((float)(slot->linearHoriAdvance/65536.0f)*scale);return 0;
}

struct SymbolPath {double *data;int count,capacity,started;float x,y;};
static int symbol_segment(struct SymbolPath *p,int type,const FT_Vector *a,const FT_Vector *b,const FT_Vector *c) {
    int points=(a!=NULL)+(b!=NULL)+(c!=NULL);
    if(p->count+1+points*2>p->capacity)return 1;
    p->data[p->count++]=type;
    const FT_Vector *v[3]={a,b,c};
    for(int i=0;i<points;i++){p->data[p->count++]=(float)((float)(v[i]->x/64.0f)+p->x);p->data[p->count++]=(float)((float)(-v[i]->y/64.0f)+p->y);}
    return 0;
}
static int symbol_move(const FT_Vector *a,void *user){struct SymbolPath *p=user;if(p->started&&symbol_segment(p,4,NULL,NULL,NULL))return 1;p->started=1;return symbol_segment(p,0,a,NULL,NULL);}
static int symbol_line(const FT_Vector *a,void *p){return symbol_segment(p,1,a,NULL,NULL);}
static int symbol_quad(const FT_Vector *a,const FT_Vector *b,void *p){return symbol_segment(p,2,a,b,NULL);}
static int symbol_cubic(const FT_Vector *a,const FT_Vector *b,const FT_Vector *c,void *p){return symbol_segment(p,3,a,b,c);}

/* Result is a bounded segment stream, generated from font bytes, never JVM-supplied geometry. */
int music_symbol_outline(void *handle,int glyph,double size,double x,double y,int *info,double *data,int capacity) {
    struct MaskFace *ctx=handle;
    if(!ctx||glyph<0||!isfinite(size)||size<=0||size>4096||!isfinite(x)||!isfinite(y)||fabs(x)>4194304||fabs(y)>4194304||!info||!data||capacity<0)return -1;
    symbol_transform(ctx,size);
    int error=FT_Set_Char_Size(ctx->face,0,(FT_F26Dot6)((size<1?1:size)*64),72,72);
    if(!error)error=FT_Load_Glyph(ctx->face,(FT_UInt)glyph,FT_LOAD_NO_HINTING|FT_LOAD_NO_BITMAP);
    FT_Set_Transform(ctx->face,NULL,NULL);if(error)return error;
    FT_Outline *outline=&ctx->face->glyph->outline;
    /* StandardGlyphVector appends cached origin-zero outlines with float translation. */
    struct SymbolPath path={data,0,capacity,0,(float)x,(float)y};
    const FT_Outline_Funcs funcs={symbol_move,symbol_line,symbol_quad,symbol_cubic,0,0};
    if(outline->n_points){error=FT_Outline_Decompose(outline,&funcs,&path);if(!error)error=symbol_segment(&path,4,NULL,NULL,NULL);}
    if(error)return error;info[0]=path.count;info[1]=(outline->flags&FT_OUTLINE_EVEN_ODD_FILL)?0:1;return 0;
}
