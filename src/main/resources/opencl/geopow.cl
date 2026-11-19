#define R(x,n) (((x) >> (n)) | ((x) << (32-(n))))
__constant uint K[64] = {
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};

inline void compress(uint s[8], uint w[16]) {
    uint a=s[0],b=s[1],c=s[2],d=s[3],e=s[4],f=s[5],g=s[6],h=s[7];
    for (uint i=0;i<64;i++) {
        if (i>=16) { uint x=w[(i+1)&15], y=w[(i+14)&15];
            w[i&15] += (R(y,17)^R(y,19)^(y>>10)) + w[(i+9)&15] + (R(x,7)^R(x,18)^(x>>3)); }
        uint t1=h+(R(e,6)^R(e,11)^R(e,25))+((e&f)^(~e&g))+K[i]+w[i&15];
        uint t2=(R(a,2)^R(a,13)^R(a,22))+((a&b)^(a&c)^(b&c));
        h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
    }
    s[0]+=a;s[1]+=b;s[2]+=c;s[3]+=d;s[4]+=e;s[5]+=f;s[6]+=g;s[7]+=h;
}
inline uint load_be(__private const uchar *p) { return ((uint)p[0]<<24)|((uint)p[1]<<16)|((uint)p[2]<<8)|(uint)p[3]; }
inline void hash124(__private const uchar *m, __private uint out[8]) {
    uint s[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    uint w[16];
    for(uint b=0;b<2;b++){ for(uint i=0;i<16;i++) w[i]=load_be(m+b*64+i*4); if(b==1){w[15]=0x80000000;}
        compress(s,w); }
    // The 124-byte header is exactly 992 bits; the padding word occupies block word 15.
    out[0]=s[0];out[1]=s[1];out[2]=s[2];out[3]=s[3];out[4]=s[4];out[5]=s[5];out[6]=s[6];out[7]=s[7];
}
inline void hash32(__private uint in[8], __private uint out[8]) {
    uint s[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    uint w[16]; for(uint i=0;i<8;i++) w[i]=in[i]; w[8]=0x80000000; for(uint i=9;i<15;i++) w[i]=0; w[15]=256;
    compress(s,w); for(uint i=0;i<8;i++) out[i]=s[i];
}
__kernel void geopow(__global const uchar *header, __global const uint *target,
                     ulong base, ulong attempts, ulong stride,
                     __global volatile int *found, __global ulong *winner) {
    ulong id=get_global_id(0); if(id>=attempts || atomic_add(found,0)!=0) return;
    ulong n=base+id*stride; uchar m[128];
    for(uint i=0;i<124;i++) m[i]=header[i];
    for(uint i=0;i<8;i++) m[116+i]=(uchar)(n>>(8*i));
    // The 124-byte message uses two data blocks and one final length block.
    uint s[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    uint w[16]; for(uint i=0;i<16;i++) w[i]=load_be(m+i*4); compress(s,w);
    for(uint i=0;i<15;i++) w[i]=load_be(m+64+i*4);
    w[13]=((uint)m[116]<<24)|((uint)m[117]<<16)|((uint)m[118]<<8)|(uint)m[119];
    w[14]=((uint)m[120]<<24)|((uint)m[121]<<16)|((uint)m[122]<<8)|(uint)m[123];
    w[15]=0x80000000; compress(s,w);
    // 124-byte input needs a third SHA block for the 64-bit bit length.
    for(uint i=0;i<16;i++) w[i]=0; w[15]=992; compress(s,w);
    uint h[8]; hash32(s,h); int meets=1;
    for(uint i=0;i<8;i++){ if(h[i]<target[i]) break; if(h[i]>target[i]){meets=0;break;} }
    if(meets && atomic_cmpxchg(found,0,1)==0) *winner=n;
}
