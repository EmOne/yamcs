import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, input } from '@angular/core';
import { AlgorithmStatus, AlgorithmStatusSubscription, YamcsService } from '@yamcs/webapp-sdk';
import { BehaviorSubject } from 'rxjs';

@Component({
  templateUrl: './AlgorithmSummaryTab.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlgorithmSummaryTab implements OnInit, OnDestroy {

  qualifiedName = input.required<string>({ alias: 'algorithm' });

  algorithm$: Promise<Algorithm>;
  status$ = new BehaviorSubject<AlgorithmStatus | null>(null);

  private algorithmStatusSubscription: AlgorithmStatusSubscription;

  constructor(readonly yamcs: YamcsService) {
  }

  ngOnInit(): void {
    const instance = this.yamcs.instance!;

    this.algorithm$ = this.yamcs.yamcsClient.getAlgorithm(instance, this.qualifiedName());

    if (this.yamcs.processor) {
      this.algorithmStatusSubscription = this.yamcs.yamcsClient.createAlgorithmStatusSubscription({
        instance: this.yamcs.instance!,
        processor: this.yamcs.processor,
        name: this.qualifiedName(),
      }, status => this.status$.next(status));
    }
  }

  ngOnDestroy() {
    this.algorithmStatusSubscription?.cancel();
  }
}
