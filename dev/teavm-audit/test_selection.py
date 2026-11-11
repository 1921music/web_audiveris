import json
from pathlib import Path
import unittest
from select_roots import choose
from trace_exclusions import shortest_paths, witness, check_exclusion_guard
from classify_classes import is_component, decide


class SelectionTest(unittest.TestCase):
    def test_text_font_backend_is_separate_from_retained_engine_api(self):
        base = 'org.audiveris.omr.text.TextFontGeometry'
        for name in [base + '$AwtProvider', base + '$DefaultHolder']:
            decision, _ = decide({'class': name}, {}, {})
            self.assertEqual(decision, 'exclude')
            self.assertEqual(self.action(name, '<init>()V', decision=decision)[0], 'excluded')
        for name in [base, base + '$Provider', base + '$Measurement']:
            decision, _ = decide({'class': name}, {}, {})
            self.assertEqual(decision, 'keep')
            self.assertEqual(self.action(name, 'measure()V', decision=decision)[0], 'root')
    @classmethod
    def setUpClass(cls):
        cls.policy = json.loads(Path(__file__).with_name('root-policy.json').read_text(encoding='utf-8'))

    def action(self, cls, method, decision='mixed', category='engine'):
        root = {'class': cls, 'method': cls + '.' + method, 'category': category}
        return choose(root, decision, self.policy)

    def test_rendering_is_not_forced_but_geometry_is(self):
        name = 'org.audiveris.omr.lag.BasicSection'
        self.assertEqual(self.action(name, 'render(Ljava/awt/Graphics;)V', category='ui')[0], 'excluded')
        self.assertEqual(self.action(name, 'computeOrientedLine()Ljava/lang/Object;')[0], 'root')

    def test_training_lambdas_are_excluded_and_inference_remains(self):
        name = 'org.audiveris.omr.math.NeuralNetwork'
        for method in ['train([[D)V', 'lambda$train$0()V', 'marshal(Ljava/io/OutputStream;)V']:
            self.assertEqual(self.action(name, method)[0], 'excluded')
        for method in ['forward([D)V', 'run([D)[D', 'unmarshal(Ljava/io/InputStream;)Ljava/lang/Object;']:
            self.assertEqual(self.action(name, method)[0], 'root')

    def test_image_generation_and_musicxml_export_are_not_blanket_removed(self):
        self.assertEqual(self.action('org.audiveris.omr.image.TemplateFactory$Builder', 'buildImage()Ljava/awt/image/BufferedImage;', decision='keep')[0], 'root')
        self.assertEqual(self.action('org.audiveris.omr.sheet.Book', 'export()Z')[0], 'root')
        self.assertEqual(self.action('org.audiveris.omr.sheet.Book', 'store()V')[0], 'excluded')

    def test_boundaries_are_dependency_only_including_fonts(self):
        self.assertEqual(self.action('org.audiveris.omr.ui.symbol.MusicFont', 'getLayout()V', decision='boundary', category='ui')[0], 'dependency-only')
        self.assertEqual(self.action('org.audiveris.omr.ui.symbol.FontPrintOut', 'main()V', decision='exclude', category='ui')[0], 'excluded')

    def test_ui_wrapper_subclasses_outside_ui_packages_are_detected(self):
        self.assertTrue(is_component('org.audiveris.omr.sig.inter.HeadInter$Editor', {
            'org.audiveris.omr.sig.inter.HeadInter$Editor': {'parent': 'org.audiveris.omr.sig.ui.InterEditor'}}))
        self.assertFalse(is_component('org.audiveris.omr.math.Line', {'org.audiveris.omr.math.Line': {'parent': 'java.lang.Object'}}))
        self.assertFalse(is_component('org.audiveris.omr.ui.symbol.MusicFont', {}))
        self.assertTrue(is_component('org.audiveris.omr.sig.inter.HeadInter$Editor$Model', {
            'org.audiveris.omr.sig.inter.HeadInter$Editor': {'parent': 'org.audiveris.omr.sig.ui.InterEditor'},
            'org.audiveris.omr.sig.inter.HeadInter$Editor$Model': {'parent': 'java.lang.Object'}}))

    def test_manual_edit_is_not_rooted_but_link_search_is(self):
        name = 'org.audiveris.omr.sig.inter.HeadInter'
        self.assertEqual(self.action(name, 'deriveFrom()Z')[0], 'excluded')
        self.assertEqual(self.action(name, 'searchLinks()Ljava/util/Collection;')[0], 'root')

    def test_path_search_handles_cycles_multiple_roots_and_unreachable_nodes(self):
        graph = {'root-b': {'loop'}, 'root-a': {'ui'}, 'loop': {'root-b', 'ui'}, 'ui': {'leaf'}}
        parents = shortest_paths({'root-a', 'root-b'}, graph)
        self.assertEqual(witness('leaf', parents), ['root-a', 'ui', 'leaf'])
        self.assertEqual(witness('absent', parents), [])
        for node in parents:
            path = witness(node, parents)
            self.assertIn(path[0], {'root-a', 'root-b'})
            for caller, callee in zip(path, path[1:]):
                self.assertIn(callee, graph[caller])

    def test_guard_rejects_forced_roots_and_reports_transitive_leaks(self):
        summary = {'excludedClassesForcedAsRoots': 0, 'excludedMethodsForcedAsRoots': 0, 'strictExclusionPass': False}
        check_exclusion_guard(summary, False)
        with self.assertRaises(SystemExit) as failure:
            check_exclusion_guard(summary, True)
        self.assertEqual(failure.exception.code, 2)
        summary['strictExclusionPass'] = True
        check_exclusion_guard(summary, True)
        summary['excludedMethodsForcedAsRoots'] = 1
        with self.assertRaises(AssertionError):
            check_exclusion_guard(summary, False)


if __name__ == '__main__':
    unittest.main()
