from pathlib import Path
from PIL import Image, ImageDraw

root = Path(__file__).parent
references = Path(r'C:\Fiskentra\Fiskentra-prototype-app\design\fiskentra-full-structure')
pairs = [
    ('home', '01-planning-and-forecast-en.png', (17, 123, 360, 957), 'implementation-home-pass2.png'),
    ('saved', '03-journal-saved-devices-en.png', (736, 52, 1049, 1033), 'implementation-saved-pass2.png'),
    ('journal', '03-journal-saved-devices-en.png', (23, 52, 350, 1033), 'implementation-journal-pass2.png'),
    ('map', '02-map-and-field-trip-en.png', (23, 73, 350, 1010), 'implementation-map-pass2.png'),
]
for name, reference, box, screenshot in pairs:
    if not (root / screenshot).exists(): continue
    source = Image.open(references / reference).convert('RGB').crop(box)
    actual = Image.open(root / screenshot).convert('RGB').crop((0, 120, 1080, 2245))
    actual = actual.resize((source.width, round(actual.height * source.width / actual.width)))
    combined = Image.new('RGB', (source.width + actual.width + 40, source.height + 35), '#102030')
    combined.paste(source, (0, 35))
    combined.paste(actual, (source.width + 40, 35))
    draw = ImageDraw.Draw(combined)
    draw.text((8, 8), 'Reference (board crop)', fill='white')
    draw.text((source.width + 48, 8), 'Native rebuild (equal width)', fill='white')
    combined.save(root / f'comparison-{name}-pass2.png')
