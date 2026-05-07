import 'dart:io';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';
import 'package:video_player/video_player.dart';

import '../models/saved_media_item.dart';
import '../models/sub_profile.dart';
import '../services/device_media_service.dart';
import '../services/sub_profile_repository.dart';

class RelationshipCenterScreen extends StatelessWidget {
  const RelationshipCenterScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final subRepo = context.watch<SubProfileRepository>();
    final mediaService = context.watch<DeviceMediaService>();
    final activeSub = subRepo.activeSub;
    final scopedItems = mediaService.itemsForSub(activeSub?.id);

    return Scaffold(
      appBar: AppBar(title: const Text('Relationship Center')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _Section(
            title: 'Dom Preferences',
            child: Column(
              children: SubProfileRepository.metrics
                  .map(
                    (metric) => _PreferenceSlider(
                      label: metric,
                      value: subRepo.domPreferences[metric] ?? 50,
                      onChanged: (v) =>
                          subRepo.updateDomPreference(metric, v.round()),
                    ),
                  )
                  .toList(),
            ),
          ),
          const SizedBox(height: 16),
          _Section(
            title: 'Multi-Sub Profiles',
            trailing: IconButton(
              onPressed: () => _showAddSubDialog(context),
              icon: const Icon(Icons.add),
            ),
            child: Column(
              children: subRepo.subs
                  .map(
                    (sub) => _SubTile(
                      sub: sub,
                      isActive: sub.id == activeSub?.id,
                      score: subRepo.compatibilityFor(sub),
                      summary: subRepo.compatibilitySummary(sub),
                      onActivate: () => subRepo.setActiveSub(sub.id),
                      onDelete: subRepo.subs.length <= 1
                          ? null
                          : () => subRepo.removeSub(sub.id),
                    ),
                  )
                  .toList(),
            ),
          ),
          if (activeSub != null) ...[
            const SizedBox(height: 16),
            _Section(
              title: 'Compatibility Settings — ${activeSub.name}',
              trailing: FilledButton.tonalIcon(
                onPressed: () => _saveCompatibilityReport(
                  context,
                  subRepo: subRepo,
                  sub: activeSub,
                ),
                icon: const Icon(Icons.save_alt),
                label: const Text('Save Report'),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final metric in SubProfileRepository.metrics)
                    _PreferenceSlider(
                      label: metric,
                      value: activeSub.preferences[metric] ?? 50,
                      onChanged: (v) => subRepo.updateSubPreference(
                        activeSub.id,
                        metric,
                        v.round(),
                      ),
                    ),
                  const SizedBox(height: 8),
                  Text(
                    'Compatibility: ${subRepo.compatibilityFor(activeSub).toStringAsFixed(1)}% '
                    '(${subRepo.compatibilitySummary(activeSub)})',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            _Section(
              title: 'Device Media Library',
              trailing: Wrap(
                spacing: 8,
                children: [
                  OutlinedButton.icon(
                    onPressed: () => mediaService.addImages(subId: activeSub.id),
                    icon: const Icon(Icons.photo_library_outlined),
                    label: const Text('Add Images'),
                  ),
                  OutlinedButton.icon(
                    onPressed: () => mediaService.addVideo(subId: activeSub.id),
                    icon: const Icon(Icons.video_library_outlined),
                    label: const Text('Add Video'),
                  ),
                ],
              ),
              child: scopedItems.isEmpty
                  ? const Text('No media saved for this sub yet.')
                  : Column(
                      children: scopedItems
                          .map((item) => _MediaTile(item: item))
                          .toList(),
                    ),
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _showAddSubDialog(BuildContext context) async {
    final controller = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Add Sub Profile'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(
            labelText: 'Name',
            border: OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, controller.text.trim()),
            child: const Text('Add'),
          ),
        ],
      ),
    );

    if (name == null || name.trim().isEmpty || !context.mounted) return;
    await context.read<SubProfileRepository>().addSub(name);
  }

  Future<void> _saveCompatibilityReport(
    BuildContext context, {
    required SubProfileRepository subRepo,
    required SubProfile sub,
  }) async {
    final content = StringBuffer()
      ..writeln('Compatibility Report')
      ..writeln('Generated: ${DateTime.now().toIso8601String()}')
      ..writeln('Sub: ${sub.name}')
      ..writeln('Score: ${subRepo.compatibilityFor(sub).toStringAsFixed(1)}%')
      ..writeln('Summary: ${subRepo.compatibilitySummary(sub)}')
      ..writeln('')
      ..writeln('Metric breakdown:');

    for (final metric in SubProfileRepository.metrics) {
      final dom = subRepo.domPreferences[metric] ?? 50;
      final subPref = sub.preferences[metric] ?? 50;
      content.writeln('- $metric: dom=$dom sub=$subPref Δ=${(dom - subPref).abs()}');
    }

    final savedPath = await context.read<DeviceMediaService>().saveTextReport(
          filenamePrefix: 'compatibility_${sub.name.replaceAll(' ', '_')}',
          content: content.toString(),
        );

    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('Saved report: $savedPath')),
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.child, this.trailing});

  final String title;
  final Widget child;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    title,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                if (trailing != null) trailing!,
              ],
            ),
            const SizedBox(height: 8),
            child,
          ],
        ),
      ),
    );
  }
}

class _PreferenceSlider extends StatelessWidget {
  const _PreferenceSlider({
    required this.label,
    required this.value,
    required this.onChanged,
  });

  final String label;
  final int value;
  final ValueChanged<double> onChanged;

  @override
  Widget build(BuildContext context) {
    final safeLabel = label.isEmpty ? 'Preference' : label;
    final title = '${safeLabel[0].toUpperCase()}${safeLabel.substring(1)}';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('$title: $value'),
        Slider(
          value: value.toDouble(),
          min: 0,
          max: 100,
          divisions: 100,
          label: '$value',
          onChanged: onChanged,
        ),
      ],
    );
  }
}

class _SubTile extends StatelessWidget {
  const _SubTile({
    required this.sub,
    required this.isActive,
    required this.score,
    required this.summary,
    required this.onActivate,
    this.onDelete,
  });

  final SubProfile sub;
  final bool isActive;
  final double score;
  final String summary;
  final VoidCallback onActivate;
  final VoidCallback? onDelete;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      title: Text(sub.name),
      subtitle: Text('$summary • ${score.toStringAsFixed(1)}%'),
      leading: Icon(
        isActive ? Icons.radio_button_checked : Icons.radio_button_unchecked,
      ),
      trailing: Wrap(
        spacing: 8,
        children: [
          IconButton(
            onPressed: onActivate,
            icon: const Icon(Icons.check_circle_outline),
            tooltip: 'Set active',
          ),
          if (onDelete != null)
            IconButton(
              onPressed: onDelete,
              icon: const Icon(Icons.delete_outline),
              tooltip: 'Delete',
            ),
        ],
      ),
      onTap: onActivate,
    );
  }
}

class _MediaTile extends StatelessWidget {
  const _MediaTile({required this.item});

  final SavedMediaItem item;

  @override
  Widget build(BuildContext context) {
    final dt = DateFormat('MMM d, h:mm a')
        .format(DateTime.fromMillisecondsSinceEpoch(item.savedAtMs));

    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: item.type == SavedMediaType.image
          ? ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: Image.file(
                File(item.path),
                width: 56,
                height: 56,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => const Icon(Icons.broken_image),
              ),
            )
          : const SizedBox(
              width: 56,
              height: 56,
              child: Icon(Icons.videocam),
            ),
      title: Text(item.type == SavedMediaType.image ? 'Image' : 'Video'),
      subtitle: Text('$dt\n${item.path}'),
      isThreeLine: true,
      onTap: () {
        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (_) => item.type == SavedMediaType.image
                ? _ImageViewer(path: item.path)
                : _VideoViewer(path: item.path),
          ),
        );
      },
      trailing: IconButton(
        onPressed: () => context.read<DeviceMediaService>().removeItem(item.id),
        icon: const Icon(Icons.delete_outline),
      ),
    );
  }
}

class _ImageViewer extends StatelessWidget {
  const _ImageViewer({required this.path});

  final String path;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Image Viewer')),
      body: Center(
        child: InteractiveViewer(child: Image.file(File(path))),
      ),
    );
  }
}

class _VideoViewer extends StatefulWidget {
  const _VideoViewer({required this.path});

  final String path;

  @override
  State<_VideoViewer> createState() => _VideoViewerState();
}

class _VideoViewerState extends State<_VideoViewer> {
  late final VideoPlayerController _controller;
  bool _ready = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _controller = VideoPlayerController.file(File(widget.path))
      ..initialize().then((_) {
        if (mounted) {
          setState(() => _ready = true);
        }
      }).catchError((Object error) {
        if (mounted) {
          setState(() => _error = error.toString());
        }
      });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Video Viewer')),
      body: Center(
        child: _error != null
            ? Text('Unable to load video: $_error')
            : !_ready
                ? const CircularProgressIndicator()
                : Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  AspectRatio(
                    aspectRatio: _controller.value.aspectRatio,
                    child: VideoPlayer(_controller),
                  ),
                  const SizedBox(height: 16),
                  FilledButton.icon(
                    onPressed: () {
                      if (_controller.value.isPlaying) {
                        _controller.pause();
                      } else {
                        _controller.play();
                      }
                      setState(() {});
                    },
                    icon: Icon(
                      _controller.value.isPlaying ? Icons.pause : Icons.play_arrow,
                    ),
                    label: Text(_controller.value.isPlaying ? 'Pause' : 'Play'),
                  ),
                ],
              ),
      ),
    );
  }
}
