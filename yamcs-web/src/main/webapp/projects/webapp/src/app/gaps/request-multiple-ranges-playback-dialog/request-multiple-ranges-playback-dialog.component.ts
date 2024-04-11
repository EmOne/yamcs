import { Component, Inject } from '@angular/core';
import { UntypedFormControl, UntypedFormGroup, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Gap, PlaybackRange, SelectOption, WebappSdkModule, YamcsService, utils } from '@yamcs/webapp-sdk';
import { BehaviorSubject } from 'rxjs';

@Component({
  standalone: true,
  templateUrl: './request-multiple-ranges-playback-dialog.component.html',
  imports: [
    WebappSdkModule,
  ],
})
export class RequestMultipleRangesPlaybackDialogComponent {

  gaps: Gap[];
  linkOptions$ = new BehaviorSubject<SelectOption[]>([]);

  form = new UntypedFormGroup({
    mergeTolerance: new UntypedFormControl(30),
    link: new UntypedFormControl('', Validators.required),
  });

  constructor(
    private dialogRef: MatDialogRef<RequestMultipleRangesPlaybackDialogComponent>,
    private yamcs: YamcsService,
    @Inject(MAT_DIALOG_DATA) readonly data: any,
  ) {
    this.gaps = this.data.gaps;

    this.yamcs.yamcsClient.getLinks(yamcs.instance!).then(links => {
      const linkOptions = [];
      for (const link of links) {
        if (link.type.indexOf('DassPlaybackPacketProvider') !== -1) {
          linkOptions.push({
            id: link.name,
            label: link.name,
          });
        }
      }
      this.linkOptions$.next(linkOptions);
      if (linkOptions.length) {
        this.form.get('link')!.setValue(linkOptions[0].id);
      }
    });
  }

  sendRequest() {
    const ranges = [];
    const rangeCache = new Map<number, PlaybackRange>();
    const tolerance = this.form.value['mergeTolerance'] * 60 * 1000;

    this.gaps.sort((a, b) => a.start.localeCompare(b.start));
    for (const gap of this.gaps) {
      const prev = rangeCache.get(gap.apid);
      if (prev && (this.toMillis(gap.start) - this.toMillis(prev.stop)) < tolerance) {
        prev.stop = gap.stop;
      } else {
        const range = {
          apid: gap.apid,
          start: gap.start,
          stop: gap.stop,
        };
        ranges.push(range);
        rangeCache.set(gap.apid, range);
      }
    }

    this.dialogRef.close({
      link: this.form.value['link'],
      ranges,
    });
  }

  private toMillis(dateString: string) {
    return utils.toDate(dateString).getTime();
  }
}
