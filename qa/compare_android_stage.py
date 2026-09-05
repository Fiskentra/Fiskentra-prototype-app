"""Pair lower-scroll captures with full reference panels, without distorting density."""
from pathlib import Path
from PIL import Image, ImageDraw

root=Path(__file__).parent
refs=Path(r'C:\Fiskentra\Fiskentra-prototype-app\design\fiskentra-full-structure')
panels=[('forecastDetail','01-planning-and-forecast-en.png',(375,123,716,963)),
        ('tripSummary','03-journal-saved-devices-en.png',(376,52,710,1033)),
        ('profileSetup','05-registration-en.png',(730,67,1068,1009))]
for name,board,box in panels:
    path=root/f'v0162-lower-{name}.png'
    if not path.exists(): continue
    source=Image.open(refs/board).convert('RGB').crop(box)
    actual=Image.open(path).convert('RGB').crop((0,120,1080,2245))
    actual=actual.resize((source.width,round(actual.height*source.width/actual.width)))
    pair=Image.new('RGB',(source.width*2+20,max(source.height,actual.height)+28),'#102030')
    pair.paste(source,(0,28));pair.paste(actual,(source.width+20,28))
    draw=ImageDraw.Draw(pair);draw.text((4,5),f'{name}: source',fill='white');draw.text((source.width+24,5),'Native: scrolled to bottom',fill='white')
    pair.save(root/f'v0162-compare-lower-{name}.png')
