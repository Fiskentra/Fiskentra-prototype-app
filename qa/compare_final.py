"""Compare native captures with board panels at equal width without stretching."""
from pathlib import Path
from PIL import Image, ImageDraw
root=Path(__file__).parent
refs=Path(r'C:\Fiskentra\Fiskentra-prototype-app\design\fiskentra-full-structure')
groups={
 'core': [('home','01-planning-and-forecast-en.png',(17,123,360,957)),('map','02-map-and-field-trip-en.png',(23,73,350,1010)),('mapTools','02-map-and-field-trip-en.png',(376,73,703,1010)),('log','03-journal-saved-devices-en.png',(23,52,350,1033)),('saved','03-journal-saved-devices-en.png',(736,52,1049,1033)),('device','03-journal-saved-devices-en.png',(1078,52,1406,1033))],
 'auth': [('onboarding','04-authentication-en.png',(23,95,352,1008)),('signin','04-authentication-en.png',(376,95,710,1008)),('recover','04-authentication-en.png',(730,95,1060,1008)),('resetSent','04-authentication-en.png',(1081,95,1413,1008)),('checkEmail','05-registration-en.png',(375,67,713,1009)),('signup','05-registration-en.png',(19,67,355,1009))],
 'setup': [('profileSetup','05-registration-en.png',(730,67,1068,1009)),('flicRequired','05-registration-en.png',(1087,67,1426,1009)),('step0','06-first-run-flic-en.png',(18,63,351,1008)),('step1','06-first-run-flic-en.png',(368,63,709,1008)),('step2','06-first-run-flic-en.png',(726,63,1067,1008)),('step3','06-first-run-flic-en.png',(1084,63,1422,1008))],
 'details': [('catchEdit','02-map-and-field-trip-en.png',(1086,73,1421,1010)),('tripActive','02-map-and-field-trip-en.png',(735,73,1056,1010)),('tripSummary','03-journal-saved-devices-en.png',(376,52,710,1033)),('forecastDetail','01-planning-and-forecast-en.png',(375,123,716,963)),('species','01-planning-and-forecast-en.png',(730,123,1067,974)),('tripSetup','01-planning-and-forecast-en.png',(1083,123,1427,975))]
}
for group,panels in groups.items():
    comparisons=[]
    for name,board,box in panels:
        path=root/f'final-{name}.png'
        if not path.exists():continue
        source=Image.open(refs/board).convert('RGB').crop(box)
        actual=Image.open(path).convert('RGB').crop((0,120,1080,2245))
        if actual.getextrema()==((0,0),(0,0),(0,0)):continue
        actual=actual.resize((source.width,round(actual.height*source.width/actual.width)))
        paired=Image.new('RGB',(source.width*2+20,max(source.height,actual.height)+28),'#102030')
        paired.paste(source,(0,28));paired.paste(actual,(source.width+20,28));draw=ImageDraw.Draw(paired);draw.text((4,5),f'{name}: source',fill='white');draw.text((source.width+24,5),'Native (shorter viewport)',fill='white')
        paired.save(root/f'compare-final-{name}.png');comparisons.append(paired)
        # Focused comparison: shared top UI region at equal width, no distortion.
        paired.crop((0,0,paired.width,min(360,paired.height))).save(root/f'focus-final-{name}.png')
    if comparisons:
        thumbs=[im.resize((round(im.width*600/im.height),600)) for im in comparisons]
        sheet=Image.new('RGB',(sum(im.width for im in thumbs),600),'#102030');x=0
        for im in thumbs:sheet.paste(im,(x,0));x+=im.width
        sheet.save(root/f'contact-final-{group}.png')
