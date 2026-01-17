# MobileRoomReconstruction

MobileRoomReconstruction is an experimental Android project focused on mobile-based 3D room reconstruction using a sequence of photographs captured with a smartphone. The project explores the full pipeline of scene capture, approximate geometric reconstruction, texture projection, and real-time visualization using OpenGL ES.

This project is research-oriented and educational in nature. It is not intended to compete with professional photogrammetry solutions such as COLMAP, RealityCapture, or commercial LiDAR-based systems, but rather to demonstrate how a simplified end-to-end reconstruction pipeline can be implemented directly on a mobile device.

The application allows the user to capture a large number of images of an indoor scene from different viewpoints. Along with the images, camera pose information is stored and later used to approximate the spatial structure of the room. Based on this data, a triangular mesh is generated and textured using the captured photographs. The resulting model can be viewed interactively inside the application and exported for external inspection.

Core features of the project include image-based room capture, storage of camera poses, generation of an unindexed triangle mesh, texture atlas construction, UV coordinate generation, OpenGL ES–based rendering with basic lighting, and export of reconstructed geometry in PLY format.

The reconstruction pipeline works as follows. First, the user captures a sequence of images while moving the phone around the room. For each frame, camera pose data is collected. Second, an approximate geometric representation of the room is constructed based on the accumulated camera poses and heuristic surface assumptions. Third, the captured images are projected onto the mesh, producing a texture atlas and corresponding UV coordinates. Finally, the textured mesh is rendered using OpenGL ES with an orbit-style camera for inspection.

Due to the simplified nature of the reconstruction process, the resulting geometry is approximate and may contain distortions, missing surfaces, or texture artifacts. The quality of the reconstruction strongly depends on the number of images, camera motion coverage, lighting conditions, and scene complexity. In practice, capturing several hundred images with smooth motion and consistent lighting yields noticeably better results than sparse or irregular capture sequences.

The project is implemented entirely in Kotlin and targets Android devices supporting OpenGL ES 2.0. Rendering is handled manually without external 3D engines, allowing fine-grained control over buffers, shaders, and texture handling. Mesh generation and texture baking are implemented directly in the application code.

This repository is suitable as a demonstration project for topics such as mobile computer vision, 3D graphics programming, experimental photogrammetry pipelines, and applied OpenGL ES development on Android. It can also serve as a foundation for further research or extension, such as improved geometry estimation, multi-view consistency checks, better texture blending, or integration with external reconstruction libraries.

Limitations of the current implementation include approximate geometry reconstruction, lack of global optimization (bundle adjustment), visible texture seams, and limited robustness to motion blur or poor lighting. These limitations are known and documented as part of the experimental scope of the project.

Future improvements may include more robust surface reconstruction, view selection for texture baking, improved texture blending, depth-based constraints, and support for exporting additional formats compatible with common 3D tools.

This project is provided as-is for educational and research purposes.
