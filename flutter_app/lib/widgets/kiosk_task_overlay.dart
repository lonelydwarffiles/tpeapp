import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/kiosk_task_controller.dart';

class KioskTaskOverlay extends StatelessWidget {
  const KioskTaskOverlay({super.key});

  @override
  Widget build(BuildContext context) {
    return Consumer<KioskTaskController>(
      builder: (context, controller, _) {
        if (!controller.isActive) {
          return const SizedBox.shrink();
        }
        final remaining = controller.remaining;
        final minutes = remaining.inMinutes.remainder(60).toString().padLeft(2, '0');
        final seconds = remaining.inSeconds.remainder(60).toString().padLeft(2, '0');
        final hours = remaining.inHours.toString().padLeft(2, '0');
        final cs = Theme.of(context).colorScheme;

        return PopScope(
          canPop: false,
          child: Material(
            color: Colors.black.withOpacity(0.92),
            child: SafeArea(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Container(
                    width: 560,
                    padding: const EdgeInsets.all(24),
                    decoration: BoxDecoration(
                      color: cs.surfaceContainerHigh,
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(color: cs.primary.withOpacity(0.45)),
                    ),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.assignment_late_rounded, size: 52),
                        const SizedBox(height: 14),
                        Text(
                          'Assigned Task',
                          style: Theme.of(context).textTheme.headlineSmall,
                          textAlign: TextAlign.center,
                        ),
                        const SizedBox(height: 12),
                        Text(
                          controller.taskDescription,
                          style: Theme.of(context).textTheme.titleMedium,
                          textAlign: TextAlign.center,
                        ),
                        const SizedBox(height: 20),
                        Text(
                          '$hours:$minutes:$seconds',
                          style: Theme.of(context).textTheme.displaySmall?.copyWith(
                                fontWeight: FontWeight.w700,
                                color: cs.primary,
                              ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'Kiosk lock is active until timer ends or admin clears this task.',
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                color: cs.onSurfaceVariant,
                              ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}
