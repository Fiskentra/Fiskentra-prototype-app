"""Read-only comparison of private pre-update tar and post-update captures. Reports no coordinates."""
import argparse
import hashlib
import json
import tarfile
import xml.etree.ElementTree as ET
from pathlib import Path


def preferences(archive, name):
    member = next(item for item in archive.getmembers() if item.name.endswith('/' + name + '.xml'))
    root = ET.fromstring(archive.extractfile(member).read())
    result = {}
    for item in root:
        name = item.attrib['name']
        if item.tag == 'string': result[name] = item.text or ''
        elif item.tag in ('int', 'long'): result[name] = int(item.attrib['value'])
        elif item.tag == 'float': result[name] = float(item.attrib['value'])
        elif item.tag == 'boolean': result[name] = item.attrib['value'] == 'true'
        elif item.tag == 'set': result[name] = sorted(child.text or '' for child in item)
    return result


def photos(archive):
    return {item.name: hashlib.sha256(archive.extractfile(item).read()).hexdigest()
            for item in archive.getmembers() if item.isfile() and 'catch_photos/' in item.name}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--before', required=True)
    parser.add_argument('--after-ledger', required=True)
    parser.add_argument('--after-data', required=True)
    parser.add_argument('--report', required=True)
    args = parser.parse_args()
    ledger = json.loads(Path(args.after_ledger).read_text(encoding='utf-8-sig'))
    with tarfile.open(args.before) as before, tarfile.open(args.after_data) as after:
        original = json.loads(preferences(before, 'fiskentra_points')['saved_points'])
        rows = {int(key): value for key, value in ledger['points'].items()}
        defaults = {'title': '', 'symbol': '', 'color': 0, 'size': 18, 'note': '', 'type': 'Moment'}
        fields = ['id', 'lat', 'lon', 'time', 'type', 'note', 'title', 'symbol', 'color', 'size', 'weather', 'catch_details']
        changed = {key: 0 for key in fields}
        missing = 0
        for point in original:
            row = rows.get(point['id'])
            if row is None:
                missing += 1
                continue
            for field in fields:
                if point.get(field, defaults.get(field)) != row.get(field, defaults.get(field)):
                    changed[field] += 1
        old_track = preferences(before, 'fiskentra_track')
        new_track = preferences(after, 'fiskentra_track')
        old_geometry = json.loads(old_track.get('points', '[]'))
        new_geometry = json.loads(new_track.get('points', '[]'))
        old_trips = json.loads(preferences(before, 'fiskentra_fishing_days').get('sessions', '[]'))
        new_trips = json.loads(preferences(after, 'fiskentra_fishing_days').get('sessions', '[]'))
        old_map = preferences(before, 'field_map')
        new_map = preferences(after, 'field_map')
        old_photos, new_photos = photos(before), photos(after)
        report = {
            'pointCountBefore': len(original), 'pointCountAfter': len(rows),
            'missingLegacyPointCount': missing,
            'changedLegacyFieldCounts': changed,
            'legacyPointBackupExact': json.loads(ledger['legacyBackup']['points']) == original,
            'trackPointsBefore': len(old_geometry), 'trackPointsAfter': len(new_geometry),
            'trackGeometryAndSegmentsExact': old_geometry == new_geometry,
            'trackSegmentBreaksBefore': sum(bool(p.get('segment')) for p in old_geometry),
            'trackSegmentBreaksAfter': sum(bool(p.get('segment')) for p in new_geometry),
            'changedTrackPreferenceKeys': sorted(key for key, value in old_track.items() if new_track.get(key) != value),
            'archivedTripsBefore': len(old_trips), 'archivedTripsAfter': len(new_trips),
            'archivePayloadExact': old_trips == new_trips,
            'archivedRoutePointsBefore': sum(len(trip.get('route', [])) for trip in old_trips),
            'archivedRoutePointsAfter': sum(len(trip.get('route', [])) for trip in new_trips),
            'archivedSegmentBreaksBefore': sum(bool(p.get('segment')) for trip in old_trips for p in trip.get('route', [])),
            'archivedSegmentBreaksAfter': sum(bool(p.get('segment')) for trip in new_trips for p in trip.get('route', [])),
            'changedMapPreferenceKeys': sorted(key for key, value in old_map.items() if new_map.get(key) != value),
            'photoFilesBefore': len(old_photos), 'photoFilesAfter': len(new_photos),
            'originalPhotoFilesExact': all(new_photos.get(name) == digest for name, digest in old_photos.items()),
            'originalCatchPhotoLinks': sum(bool(p.get('catch_details', {}).get('local_photo_path')) for p in original),
            'newUnassignedTripCount': sum(p.get('tripId', 0) == 0 for p in rows.values()),
            'tombstoneCount': len(ledger['deleted']),
        }
        report['status'] = 'PASS' if (not missing and not any(changed.values())
                and report['legacyPointBackupExact'] and report['trackGeometryAndSegmentsExact']
                and report['archivePayloadExact'] and report['originalPhotoFilesExact']) else 'REVIEW'
        Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__': main()
