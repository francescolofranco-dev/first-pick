#!/usr/bin/env python3

r1 = 1.777
cardW_1 = 0.108
colGap_1 = 0.133
gridLeft_1 = 0.110
cardH_1 = 0.268
rowGap_1 = 0.275
gridTop_1 = 0.215

r2 = 1.686
cardW_2 = 0.114
colGap_2 = 0.132
gridLeft_2 = 0.144
cardH_2 = 0.340
rowGap_2 = 0.404
gridTop_2 = 0.259

print(f"val rCoerced = actualRatio.coerceIn(1.3f, 2.4f)")
print(f"val t = (rCoerced - {r1}f) / ({r2}f - {r1}f)")
print("fun interp(v1: Float, v2: Float) = v1 + (v2 - v1) * t")
print(f"val cardW = interp({cardW_1}f, {cardW_2}f) * gameWidth")
print(f"val colGap = interp({colGap_1}f, {colGap_2}f) * gameWidth")
print(f"val gridLeft = interp({gridLeft_1}f, {gridLeft_2}f) * gameWidth")
print(f"val cardH = interp({cardH_1}f, {cardH_2}f) * gameHeight")
print(f"val rowGap = interp({rowGap_1}f, {rowGap_2}f) * gameHeight")
print(f"val gridTop = interp({gridTop_1}f, {gridTop_2}f) * gameHeight")
